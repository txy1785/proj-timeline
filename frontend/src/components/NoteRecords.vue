<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { Attachment, NoteRecord } from '../api/types'
import type { NoteDraft } from '../composables/useNoteDraft'
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
  /** 页面持有的草稿；区块卸载后草稿仍保留，供出错页展示与离开提示使用。 */
  draft: NoteDraft
  /** 页面层的保存中/状态切换等忙碌标志，期间禁止改删记录。 */
  busy?: boolean
}>()
const emit = defineEmits<{
  (e: 'uploaded', attachment: Attachment): void
  (e: 'removed', attachmentId: number): void
  (e: 'count', count: number): void
}>()

const app = useAppStore()
const notes = ref<NoteRecord[]>([])
const loadError = ref('')
const draft = props.draft.state
const composerRef = ref<HTMLElement>()
const uploading = ref(0)
const saving = ref(false)
const deleting = ref(0)
let generation = 0

const pending = computed(() => !!props.busy || saving.value || deleting.value > 0 || uploading.value > 0)
const hasDraft = props.draft.hasDraft
/** 切换新建/编辑或换记录时重建编辑器，复位源码模式与「本次上传」chips。 */
const editorKey = computed(() => `note-editor-${props.ownerType}-${props.ownerId}-${draft.editingId ?? 'new'}`)

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
  if (!draft.title.trim()) draft.title = nextTitle()
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
  notes.value = []; uploading.value = 0
  void load()
}, { immediate: true })

async function save(): Promise<boolean> {
  if (saving.value) return false
  const currentEdit = draft.editingId
  if (!props.canWrite && currentEdit === null) return false
  const content = draft.text.trim()
  if (!content) { ElMessage.warning('请先填写记录内容'); return false }
  if (content.length > 20000) { ElMessage.warning('记录不能超过 20000 字'); return false }
  const title = markdownFileName(draft.title)
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
    props.draft.reset()
    await load()
    return true
  } catch (e) { ElMessage.error((e as Error).message); return false }
  finally { saving.value = false }
}

async function edit(note: NoteRecord) {
  if (!props.canManage(note) || pending.value || draft.editingId === note.id) return
  if (hasDraft.value) {
    try {
      await ElMessageBox.confirm('当前未保存的修改将丢弃，继续编辑这份记录？', '编辑记录', {
        type: 'warning', confirmButtonText: '继续编辑', cancelButtonText: '保留当前'
      })
    } catch { return }
  }
  props.draft.startEdit(note)
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
  props.draft.reset()
  draft.title = nextTitle()
}

async function remove(note: NoteRecord) {
  if (!props.canManage(note) || pending.value) return
  try { await ElMessageBox.confirm('删除后不能恢复这条记录，确定删除？', '删除记录', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' }) }
  catch { return }
  deleting.value = note.id
  try {
    await api('DELETE', `${props.itemPath}/${note.id}`)
    if (draft.editingId === note.id) props.draft.reset()
    await load(); ElMessage.success('记录已删除')
  }
  catch (e) { ElMessage.error((e as Error).message) }
  finally { deleting.value = 0 }
}

defineExpose({ save, uploading, saving, deleting, reload: load })
</script>

<template>
  <div class="note-records">
    <div v-if="canWrite || draft.editingId !== null" ref="composerRef" class="note-composer">
      <div class="note-file-row">
        <label class="note-file-label" :for="`note-title-${ownerType}`">{{ draft.editingId ? '编辑记录' : '记录名称' }}</label>
        <el-input :id="`note-title-${ownerType}`" v-model="draft.title" maxlength="117" placeholder="例如：设计说明.md" aria-label="进展记录名称" />
        <span v-if="draft.editingId" class="note-editing-flag">编辑已保存记录</span>
        <span v-else class="muted">保存为 .md</span>
      </div>
      <MarkdownEditor :key="editorKey" v-model="draft.text" :owner-type="ownerType" :owner-id="ownerId" :category="category" :can-upload="canUpload" :disabled="saving" :attachments="attachments" aria-label="进展记录内容" placeholder="记录本次进展、问题或结果，支持 Markdown；直接粘贴截图或文件会自动上传" @uploaded="emit('uploaded', $event)" @removed="emit('removed', $event)" @uploading="uploading = $event" />
      <div class="note-composer-actions">
        <span class="muted">{{ draft.editingId ? '保存修改会更新原记录，并记下最后编辑时间。' : '每保存一次生成一份命名的 Markdown 记录；粘贴的文件同时出现在附件中，保存前可在编辑区下方移除。' }}</span>
        <div class="note-composer-buttons">
          <el-button v-if="draft.editingId" :disabled="pending" @click="cancelEdit">取消编辑</el-button>
          <el-button type="primary" :loading="saving" :disabled="(pending && !saving) || (draft.editingId !== null && !hasDraft)" @click="save">{{ draft.editingId ? '保存修改' : '保存记录' }}</el-button>
        </div>
      </div>
    </div>
    <div v-else-if="draft.text.trim()" class="note-composer"><p class="muted">{{ readOnlyHint }}未保存的草稿仍保留在这里，可复制后保存到其他位置。</p><el-input :model-value="draft.text" type="textarea" :rows="4" readonly aria-label="未保存的记录草稿" /></div>
    <el-alert v-if="loadError" :title="`记录暂时无法加载：${loadError}`" type="error" :closable="false" />
    <div v-else-if="!notes.length" class="empty">暂无记录<template v-if="canWrite">，可写一份 Markdown 记录补充进展或处理结果。</template></div>
    <article v-for="note in notes" :key="note.id" class="task-note">
      <div class="note-meta">
        <span class="note-file">📄 {{ note.title || '进展记录.md' }}</span>
        <strong>{{ note.authorName }}</strong>
        <time>{{ formatDateTime(note.createdAt) }}</time>
        <time v-if="note.updatedAt" class="note-edited" :title="note.updatedByName ? `最后编辑：${note.updatedByName}` : ''">编辑于 {{ formatDateTime(note.updatedAt) }}<template v-if="note.updatedByName"> · {{ note.updatedByName }}</template></time>
        <el-button v-if="canManage(note)" link type="primary" :disabled="pending || draft.editingId === note.id" @click="edit(note)">{{ draft.editingId === note.id ? '编辑中' : '编辑' }}</el-button>
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
