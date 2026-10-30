<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { Attachment, NoteRecord } from '../api/types'
import { useAppStore } from '../stores/app'
import { formatDateTime } from '../utils/format'
import { downloadMarkdown, markdownFileName } from '../utils/markdown'
import MarkdownEditor from './MarkdownEditor.vue'
import MarkdownView from './MarkdownView.vue'

/** 命名 Markdown 进展记录：列表、新建、再编辑、删除与下载；附件归属与接口路径由调用方指定。 */
const props = defineProps<{
  ownerType: 'TASK' | 'NODE'
  ownerId: number
  /** 列表与新建：`/tasks/{id}/notes`；单条改删：`/task-notes/{id}`。 */
  listPath: string
  itemPath: string
  category: string
  titlePrefix: string
  attachments: Attachment[]
  canWrite: boolean
  canUpload: boolean
  /** 作者本人或管理者；服务端仍做最终授权。 */
  canManage: (note: NoteRecord) => boolean
  readOnlyHint: string
}>()
const emit = defineEmits<{
  (e: 'uploaded', attachment: Attachment): void
  (e: 'removed', attachmentId: number): void
  (e: 'count', count: number): void
}>()

const app = useAppStore()
const notes = ref<NoteRecord[]>([])
const loadError = ref('')
const noteTitle = ref('')
const noteText = ref('')
const editingId = ref<number | null>(null)
const originalTitle = ref('')
const originalText = ref('')
const composerRef = ref<HTMLElement>()
const uploading = ref(0)
const saving = ref(false)
const deleting = ref(0)
let generation = 0

const pending = computed(() => saving.value || deleting.value > 0 || uploading.value > 0)
const hasDraft = computed(() => editingId.value
  ? noteTitle.value !== originalTitle.value || noteText.value !== originalText.value
  : noteText.value.trim().length > 0)
/** 切换新建/编辑或换记录时重建编辑器，复位源码模式与「本次上传」chips。 */
const editorKey = computed(() => `note-editor-${props.ownerType}-${props.ownerId}-${editingId.value ?? 'new'}`)

async function load() {
  const current = ++generation
  try {
    const rows = await api<NoteRecord[]>('GET', props.listPath)
    if (current !== generation) return
    notes.value = rows; loadError.value = ''
  } catch (e) {
    if (current === generation) { notes.value = []; loadError.value = (e as Error).message }
  }
  emit('count', notes.value.length)
  if (!noteTitle.value.trim()) noteTitle.value = nextTitle()
}

/** 默认按「前缀-业务日期-序号.md」命名，跳过已存在的文件名。 */
function nextTitle() {
  const base = app.businessDate ? `${props.titlePrefix}-${app.businessDate}` : props.titlePrefix
  const used = new Set(notes.value.map(note => (note.title || '').toLowerCase()))
  let sequence = 1
  while (used.has(`${base}-${sequence}.md`.toLowerCase())) sequence++
  return `${base}-${sequence}.md`
}

watch(() => props.listPath, () => {
  notes.value = []; resetComposer(); uploading.value = 0
  void load()
}, { immediate: true })

async function save(): Promise<boolean> {
  if (saving.value) return false
  const currentEdit = editingId.value
  if (!props.canWrite && currentEdit === null) return false
  const content = noteText.value.trim()
  if (!content) { ElMessage.warning('请先填写记录内容'); return false }
  if (content.length > 20000) { ElMessage.warning('记录不能超过 20000 字'); return false }
  const title = markdownFileName(noteTitle.value)
  if (title.length > 120) { ElMessage.warning('记录名称不能超过 120 字（含 .md）'); return false }
  if (/[\\/:*?"<>|]/.test(title)) { ElMessage.warning('记录名称不能包含 \\ / : * ? " < > | 等文件名字符'); return false }
  saving.value = true
  try {
    if (currentEdit !== null) {
      await api('PUT', `${props.itemPath}/${currentEdit}`, { title, content })
      ElMessage.success('记录已更新')
    } else {
      await api('POST', props.listPath, { title, content })
      ElMessage.success('进展记录已保存为 Markdown 文件')
    }
    resetComposer()
    await load()
    return true
  } catch (e) { ElMessage.error((e as Error).message); return false }
  finally { saving.value = false }
}

async function edit(note: NoteRecord) {
  if (!props.canManage(note) || pending.value || editingId.value === note.id) return
  if (hasDraft.value) {
    try {
      await ElMessageBox.confirm('当前未保存的修改将丢弃，继续编辑这份记录？', '编辑记录', {
        type: 'warning', confirmButtonText: '继续编辑', cancelButtonText: '保留当前'
      })
    } catch { return }
  }
  editingId.value = note.id
  noteTitle.value = note.title || '进展记录.md'
  noteText.value = note.content
  originalTitle.value = noteTitle.value
  originalText.value = noteText.value
  await nextTick()
  composerRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function cancelEdit() {
  if (pending.value) return
  if (hasDraft.value) {
    try {
      await ElMessageBox.confirm('放弃对这份记录的修改？', '取消编辑', {
        type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑'
      })
    } catch { return }
  }
  resetComposer()
  noteTitle.value = nextTitle()
}

function resetComposer() {
  editingId.value = null
  originalTitle.value = ''; originalText.value = ''
  noteTitle.value = ''; noteText.value = ''
}

async function remove(note: NoteRecord) {
  if (!props.canManage(note) || pending.value) return
  try { await ElMessageBox.confirm('删除后不能恢复这条记录，确定删除？', '删除记录', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' }) }
  catch { return }
  deleting.value = note.id
  try {
    await api('DELETE', `${props.itemPath}/${note.id}`)
    if (editingId.value === note.id) resetComposer()
    await load(); ElMessage.success('记录已删除')
  }
  catch (e) { ElMessage.error((e as Error).message) }
  finally { deleting.value = 0 }
}

/** 离开页面前调用：上传在途或忙碌时阻止；有草稿时提示保存或放弃。 */
async function allowLeave(): Promise<boolean> {
  if (uploading.value > 0) { ElMessage.info('附件正在上传，请稍候'); return false }
  if (saving.value || deleting.value > 0) { ElMessage.info('请先完成当前操作'); return false }
  if (!hasDraft.value) return true
  if (!props.canWrite && editingId.value === null) {
    try {
      await ElMessageBox.confirm('当前已不可写入。请先复制草稿保留；关闭后将放弃本次输入。', '未保存的进展记录', {
        type: 'warning', confirmButtonText: '放弃并关闭', cancelButtonText: '返回保留草稿', distinguishCancelAndClose: true
      })
      return true
    } catch { return false }
  }
  try {
    await ElMessageBox.confirm('记录尚未保存。可以保存后关闭，或放弃本次输入；关闭此提示可继续编辑。', '未保存的进展记录', {
      type: 'warning', confirmButtonText: '保存并关闭', cancelButtonText: '放弃并关闭', distinguishCancelAndClose: true
    })
    return await save()
  } catch (action) { return action === 'cancel' }
}

defineExpose({ allowLeave, hasDraft, pending, draft: noteText, discard: resetComposer, reload: load })
</script>

<template>
  <div class="note-records">
    <div v-if="canWrite || editingId !== null" ref="composerRef" class="note-composer">
      <div class="note-file-row">
        <label class="note-file-label" :for="`note-title-${ownerType}`">{{ editingId ? '编辑记录' : '记录名称' }}</label>
        <el-input :id="`note-title-${ownerType}`" v-model="noteTitle" maxlength="117" placeholder="例如：设计说明.md" aria-label="进展记录名称" />
        <span v-if="editingId" class="note-editing-flag">编辑已保存记录</span>
        <span v-else class="muted">保存为 .md</span>
      </div>
      <MarkdownEditor :key="editorKey" v-model="noteText" :owner-type="ownerType" :owner-id="ownerId" :category="category" :can-upload="canUpload" :disabled="saving" :attachments="attachments" aria-label="进展记录内容" placeholder="记录本次进展、问题或结果，支持 Markdown；直接粘贴截图或文件会自动上传" @uploaded="emit('uploaded', $event)" @removed="emit('removed', $event)" @uploading="uploading = $event" />
      <div class="note-composer-actions">
        <span class="muted">{{ editingId ? '保存修改会更新原记录，并记下最后编辑时间。' : '每保存一次生成一份命名的 Markdown 记录；粘贴的文件同时出现在附件中，保存前可在编辑区下方移除。' }}</span>
        <div class="note-composer-buttons">
          <el-button v-if="editingId" :disabled="pending" @click="cancelEdit">取消编辑</el-button>
          <el-button type="primary" :loading="saving" :disabled="(pending && !saving) || (editingId !== null && !hasDraft)" @click="save">{{ editingId ? '保存修改' : '保存记录' }}</el-button>
        </div>
      </div>
    </div>
    <div v-else-if="noteText.trim()" class="note-composer"><p class="muted">{{ readOnlyHint }}未保存的草稿仍保留在这里，可复制后保存到其他位置。</p><el-input :model-value="noteText" type="textarea" :rows="4" readonly aria-label="未保存的记录草稿" /></div>
    <el-alert v-if="loadError" :title="`记录暂时无法加载：${loadError}`" type="error" :closable="false" />
    <div v-else-if="!notes.length" class="empty">暂无记录<template v-if="canWrite">，可写一份 Markdown 记录补充进展或处理结果。</template></div>
    <article v-for="note in notes" :key="note.id" class="task-note">
      <div class="note-meta">
        <span class="note-file">📄 {{ note.title || '进展记录.md' }}</span>
        <strong>{{ note.authorName }}</strong>
        <time>{{ formatDateTime(note.createdAt) }}</time>
        <time v-if="note.updatedAt" class="note-edited" :title="note.updatedByName ? `最后编辑：${note.updatedByName}` : ''">编辑于 {{ formatDateTime(note.updatedAt) }}<template v-if="note.updatedByName"> · {{ note.updatedByName }}</template></time>
        <el-button v-if="canManage(note)" link type="primary" :disabled="pending || editingId === note.id" @click="edit(note)">{{ editingId === note.id ? '编辑中' : '编辑' }}</el-button>
        <el-button link type="primary" title="正文里的附件以系统内引用保存，下载的 .md 需回到系统查看附件" @click="downloadMarkdown(note.title || '进展记录.md', note.content)">下载 .md</el-button>
        <el-button v-if="canManage(note)" link type="danger" :loading="deleting === note.id" :disabled="pending && deleting !== note.id" @click="remove(note)">删除</el-button>
      </div>
      <MarkdownView :content="note.content" :attachments="attachments" />
    </article>
  </div>
</template>

<style scoped>
.note-composer{margin:8px 0 22px}.note-file-row{display:flex;align-items:center;gap:10px;margin-bottom:10px;flex-wrap:wrap}
.note-file-label{font-size:14px;color:var(--muted);flex:none}.note-file-row .el-input{flex:1 1 240px;max-width:380px}
.note-editing-flag{font-size:12px;color:var(--blue);background:#eef3fe;padding:1px 8px;border-radius:10px}
.note-composer-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:10px}.note-composer-actions .muted{font-size:12px}
.note-composer-buttons{display:flex;align-items:center;gap:8px}
.task-note{padding:18px 0;border-bottom:1px solid var(--line)}.note-meta{display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:14px}.note-meta time{color:var(--muted)}
.note-edited{font-size:13px;color:var(--blue)}
.note-file{color:var(--navy);font-weight:600;overflow-wrap:anywhere}.task-note .markdown-body{margin-top:10px}
</style>