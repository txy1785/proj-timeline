<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { Attachment, NodeItem, Task, TaskNote, TaskStatus, Timeline, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { canManageNode, canManageTask, canChangeTaskStatus, canEditTask, taskWorkItemContext, isNodeClosed } from '../utils/permissions'
import { TASK_KIND_LABELS, TASK_NEXT_ACTION, TASK_STATUS_LABELS, TASK_STATUS_TYPES } from '../utils/task'
import { BUG_NEXT_ACTION, BUG_STATUS_LABELS } from '../utils/bug'
import { formatDateTime } from '../utils/format'
import { downloadMarkdown, markdownFileName } from '../utils/markdown'
import AttachmentPanel from './AttachmentPanel.vue'
import MarkdownEditor from './MarkdownEditor.vue'
import MarkdownView from './MarkdownView.vue'
import TaskFormDialog from './TaskFormDialog.vue'

const props = defineProps<{ modelValue: boolean; nodeId: number; taskId: number }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'updated'): void; (e: 'closed'): void }>()
const auth = useAuthStore()
const app = useAppStore()
const router = useRouter()
const node = ref<NodeItem>()
const task = ref<Task>()
const members = ref<User[]>([])
const notes = ref<TaskNote[]>([])
const noteTitle = ref('')
const noteText = ref('')
const attachments = ref<Attachment[]>([])
const filesError = ref('')
const filesVersion = ref(0)
const loading = ref(false)
const error = ref('')
const notesError = ref('')
const savingNote = ref(false)
const savingStatus = ref(false)
const deletingNote = ref(0)
const activeTab = ref('description')
const editing = ref(false)
const relatedWorkItems = ref<NodeItem[]>([])
let generation = 0
const pending = computed(() => savingNote.value || savingStatus.value || deletingNote.value > 0)
const hasDraft = computed(() => noteText.value.trim().length > 0)
const canAct = computed(() => !!task.value && !loading.value && !error.value && canManageTask(auth.user, members.value, node.value, task.value))
const isBug = computed(() => task.value?.kind === 'BUG')
const origin = computed(() => task.value ? taskWorkItemContext(node.value, task.value) : node.value)
const canChangeStatus = computed(() => !!task.value && !loading.value && !error.value && canChangeTaskStatus(auth.user, members.value, node.value, task.value))
const canEdit = computed(() => !!task.value && !loading.value && !error.value && canEditTask(auth.user, members.value, node.value, task.value))
const archived = computed(() => node.value?.projectStatus === 'ARCHIVED')
const nextAction = computed(() => task.value ? (isBug.value ? BUG_NEXT_ACTION[task.value.status] : TASK_NEXT_ACTION[task.value.status]) : undefined)
const stateLabel = computed(() => task.value ? (isBug.value ? BUG_STATUS_LABELS[task.value.status] : TASK_STATUS_LABELS[task.value.status]) : '')

async function load() {
  const current = ++generation
  const nodeId = props.nodeId
  const taskId = props.taskId
  loading.value = true; error.value = ''; notesError.value = ''
  try {
    if (!Number.isSafeInteger(nodeId) || nodeId <= 0 || !Number.isSafeInteger(taskId) || taskId <= 0) throw new Error('任务链接无效')
    const value = await api<NodeItem>('GET', `/nodes/${nodeId}`)
    const found = value.tasks?.find(item => item.id === taskId && item.nodeId === nodeId)
    if (!found) throw new Error('任务已删除或不属于这个工作项')
    const [people, records, files] = await Promise.allSettled([
      api<User[]>('GET', `/projects/${value.projectId}/members`),
      api<TaskNote[]>('GET', `/tasks/${taskId}/notes`),
      api<Attachment[]>('GET', '/attachments', undefined, { ownerType: 'TASK', ownerId: taskId })
    ])
    if (current !== generation || !props.modelValue) return
    if (people.status === 'rejected') throw people.reason
    node.value = value; task.value = found; members.value = people.value
    if (records.status === 'fulfilled') notes.value = records.value
    else { notes.value = []; notesError.value = (records.reason as Error).message }
    if (files.status === 'fulfilled') { attachments.value = files.value; filesError.value = '' }
    else { attachments.value = []; filesError.value = (files.reason as Error).message }
    if (!noteTitle.value.trim()) noteTitle.value = nextNoteTitle()
  } catch (e) {
    if (current === generation) { error.value = (e as Error).message; task.value = undefined; node.value = undefined; members.value = [] }
  } finally { if (current === generation) loading.value = false }
}

/** 记录名默认按「修复记录/进展记录-业务日期-序号.md」生成；跳过已存在的文件名，删除记录后也不会重名。 */
function nextNoteTitle() {
  const prefix = isBug.value ? '修复记录' : '进展记录'
  const base = app.businessDate ? `${prefix}-${app.businessDate}` : prefix
  const used = new Set(notes.value.map(note => (note.title || '').toLowerCase()))
  let sequence = 1
  while (used.has(`${base}-${sequence}.md`.toLowerCase())) sequence++
  return `${base}-${sequence}.md`
}

watch([() => props.modelValue, () => props.nodeId, () => props.taskId], ([open, nodeId, taskId], old) => {
  if (!open) { generation++; loading.value = false; return }
  if (!old?.[0] || nodeId !== old[1] || taskId !== old[2]) {
    noteTitle.value = ''; noteText.value = ''; notes.value = []; node.value = undefined; task.value = undefined
    attachments.value = []; filesError.value = ''; filesVersion.value = 0
    activeTab.value = 'description'; relatedWorkItems.value = []
    void load()
  }
}, { immediate: true })

async function saveNote(): Promise<boolean> {
  if (!canAct.value || !task.value || savingNote.value) return false
  const content = noteText.value.trim()
  if (!content) { ElMessage.warning('请先填写记录内容'); return false }
  if (content.length > 20000) { ElMessage.warning('记录不能超过 20000 字'); return false }
  const title = markdownFileName(noteTitle.value)
  if (title.length > 120) { ElMessage.warning('记录名称不能超过 120 字（含 .md）'); return false }
  if (/[\\/:*?"<>|]/.test(title)) { ElMessage.warning('记录名称不能包含 \\ / : * ? " < > | 等文件名字符'); return false }
  savingNote.value = true
  try {
    await api('POST', `/tasks/${task.value.id}/notes`, { title, content })
    noteText.value = ''; noteTitle.value = ''
    await load()
    emit('updated'); ElMessage.success('进展记录已保存为 Markdown 文件')
    return true
  } catch (e) { ElMessage.error((e as Error).message); return false }
  finally { savingNote.value = false }
}

function onUploaded(file: Attachment) {
  attachments.value = [file, ...attachments.value]
  filesVersion.value++
}

function onAttachmentRemoved(attachmentId: number) {
  attachments.value = attachments.value.filter(item => item.id !== attachmentId)
  filesVersion.value++
}

function downloadNote(note: TaskNote) {
  downloadMarkdown(note.title || '进展记录.md', note.content)
}

async function allowLeave(): Promise<boolean> {
  if (pending.value || editing.value) { ElMessage.info('请先完成当前操作'); return false }
  if (!hasDraft.value) return true
  if (!canAct.value) {
    try {
      await ElMessageBox.confirm('当前任务已不可写入。请先复制草稿保留；关闭后将放弃本次输入。', '未保存的进展记录', {
        type: 'warning', confirmButtonText: '放弃并关闭', cancelButtonText: '返回保留草稿', distinguishCancelAndClose: true
      })
      return true
    } catch { return false }
  }
  try {
    await ElMessageBox.confirm('记录尚未保存。可以保存后关闭，或放弃本次输入；关闭此提示可继续编辑。', '未保存的进展记录', {
      type: 'warning', confirmButtonText: '保存并关闭', cancelButtonText: '放弃并关闭', distinguishCancelAndClose: true
    })
    return await saveNote()
  } catch (action) { return action === 'cancel' }
}

async function requestClose(done?: () => void) {
  if (!await allowLeave()) return
  noteText.value = ''
  if (done) done()
  else emit('update:modelValue', false)
}

onBeforeRouteLeave(async () => !props.modelValue || await allowLeave())
onBeforeRouteUpdate(async (to, from) => !props.modelValue || (to.path === from.path && to.query.task === from.query.task) || await allowLeave())
function beforeUnload(event: BeforeUnloadEvent) {
  if (props.modelValue && hasDraft.value) { event.preventDefault(); event.returnValue = '' }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => { generation++; window.removeEventListener('beforeunload', beforeUnload) })

async function changeStatus(status: TaskStatus) {
  const current = task.value
  if (!current || !canChangeStatus.value || pending.value || status === current.status) return
  savingStatus.value = true
  try {
    await api<Task>('PUT', `/tasks/${current.id}/status`, { status, expectedStatus: current.status })
    await load(); emit('updated')
    ElMessage.success(isBug.value ? `Bug已设为${BUG_STATUS_LABELS[status]}` : `任务已设为${TASK_STATUS_LABELS[status]}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
    await load(); emit('updated')
  } finally { savingStatus.value = false }
}

function canDeleteNote(note: TaskNote) {
  return !loading.value && !error.value && node.value?.projectStatus === 'ACTIVE' && !!auth.user
    && auth.user.active !== false && !auth.isViewer
    && (canManageNode(auth.user, members.value, origin.value) || note.authorId === auth.user.id)
}
async function removeNote(note: TaskNote) {
  if (!canDeleteNote(note) || pending.value) return
  try { await ElMessageBox.confirm('删除后不能恢复这条记录，确定删除？', '删除记录', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' }) }
  catch { return }
  deletingNote.value = note.id
  try { await api('DELETE', `/task-notes/${note.id}`); await load(); emit('updated'); ElMessage.success('记录已删除') }
  catch (e) { ElMessage.error((e as Error).message) }
  finally { deletingNote.value = 0 }
}

async function openEdit() {
  if (!canEdit.value || !node.value || pending.value) return
  try {
    const timeline = await api<Timeline>('GET', `/projects/${node.value.projectId}/timeline`)
    relatedWorkItems.value = [...timeline.modules.flatMap(module => module.nodes || []), ...(timeline.projectWorkItems || [])]
    editing.value = true
  } catch (e) { ElMessage.error((e as Error).message) }
}
async function afterEdit() { await load(); emit('updated') }
async function openWorkItem() {
  if (!node.value || !await allowLeave()) return
  noteText.value = ''
  emit('update:modelValue', false)
  await router.push(`/nodes/${origin.value?.id || node.value.id}`)
}
</script>

<template>
  <el-drawer :model-value="modelValue" size="min(760px, 100vw)" :with-header="false" :before-close="requestClose" :close-on-click-modal="false" class="task-detail-drawer" @update:model-value="emit('update:modelValue', $event)" @closed="emit('closed')">
    <div v-loading="loading" class="task-drawer-content">
      <header class="drawer-heading">
        <div><p class="muted">{{ node?.projectName || '项目工作' }}<template v-if="node"> / {{ origin?.name || node.name }}</template></p><h2>{{ task?.name || (loading ? '正在加载详情' : '详情') }}</h2><small v-if="task" class="muted">{{ isBug ? 'Bug · 独立跟踪修复' : '历史任务' }}</small></div>
        <el-button text :aria-label="isBug ? '关闭Bug详情' : '关闭任务详情'" :disabled="pending" @click="requestClose()">关闭</el-button>
      </header>
      <div v-if="error" class="drawer-error" role="alert"><el-alert :title="error" type="error" :closable="false" /><p class="muted">任务可能已删除，或你的读取权限已变化。</p><template v-if="noteText.trim()"><p class="muted">未保存的记录草稿，可复制后保留：</p><el-input :model-value="noteText" type="textarea" :rows="4" readonly aria-label="未保存的记录草稿" /></template><el-button @click="load">重新加载</el-button><el-button @click="requestClose()">返回列表</el-button></div>
      <template v-else-if="task && node">
        <div class="task-meta"><el-tag :type="TASK_STATUS_TYPES[task.status]">{{ stateLabel }}</el-tag><span>{{ isBug ? '负责人' : '执行人' }} {{ task.assigneeName }}</span><span>截止 {{ task.dueDate || '未设置' }}</span></div>
        <el-alert v-if="archived" title="项目已归档，任务、记录与附件只读" type="info" :closable="false" />
        <el-alert v-else-if="isBug && origin?.status === 'DONE'" title="关联功能已交付，此Bug仍可继续修复；原交付记录保持不变。" type="info" :closable="false" /><el-alert v-else-if="origin?.status === 'CANCELLED' || (!isBug && isNodeClosed(node))" title="关联工作项已关闭，内容和状态不可修改；记录与附件仍按原有权限处理。" type="info" :closable="false" />
        <div v-if="canChangeStatus || canEdit" class="task-drawer-actions">
          <el-button v-if="canChangeStatus && nextAction" type="primary" :loading="savingStatus" :disabled="pending && !savingStatus" @click="changeStatus(nextAction.next)">{{ isBug ? nextAction.label : task.status === 'DOING' ? '标记完成' : task.status === 'TODO' ? '开始任务' : '重新开始' }}</el-button>
          <el-button v-if="canEdit" :disabled="pending" @click="openEdit">{{ isBug ? '编辑Bug' : '编辑任务' }}</el-button>
          <el-dropdown v-if="canChangeStatus && task.status !== 'TODO'" :disabled="pending" @command="changeStatus('TODO')"><el-button :disabled="pending">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="TODO">{{ isBug ? '设为待修复' : '设为待完成' }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
        <el-tabs v-model="activeTab" class="task-drawer-tabs">
          <el-tab-pane label="说明" name="description">
            <dl class="task-description"><dt>类型</dt><dd>{{ isBug ? 'Bug' : TASK_KIND_LABELS[task.kind || 'FEATURE'] }}</dd><template v-if="isBug || task.relatedNodeName"><dt>关联功能</dt><dd>{{ origin?.name || task.relatedNodeName }}</dd></template><dt>描述</dt><dd>{{ task.description || '暂无描述，可在进展记录中补充执行情况。' }}</dd><template v-if="task.situation"><dt>{{ isBug ? '问题背景' : '任务情况' }}</dt><dd>{{ task.situation }}</dd></template><template v-if="task.handling"><dt>处理方式</dt><dd>{{ task.handling }}</dd></template><template v-if="!isBug"><dt>历史计划人天</dt><dd>{{ task.estimateDays ?? '尚未估算' }}</dd></template></dl>
          </el-tab-pane>
          <el-tab-pane :label="`进展记录 ${notes.length}`" name="records">
            <div v-if="canAct" class="note-composer">
              <div class="note-file-row">
                <label class="note-file-label" for="note-title">记录名称</label>
                <el-input id="note-title" v-model="noteTitle" maxlength="117" placeholder="例如：修复步骤.md" aria-label="进展记录名称" />
                <span class="muted">保存为 .md</span>
              </div>
              <MarkdownEditor v-model="noteText" owner-type="TASK" :owner-id="task.id" :can-upload="canAct && !archived" :disabled="savingNote" aria-label="进展记录内容" placeholder="记录本次进展、问题或结果，支持 Markdown；直接粘贴截图或文件会自动上传" @uploaded="onUploaded" @removed="onAttachmentRemoved" />
              <div class="note-composer-actions">
                <span class="muted">每保存一次生成一份命名的 Markdown 记录；粘贴的文件同时出现在「附件」，保存前可在编辑区下方移除。</span>
                <el-button type="primary" :loading="savingNote" :disabled="pending && !savingNote" @click="saveNote">保存记录</el-button>
              </div>
            </div>
            <div v-else-if="noteText.trim()" class="note-composer"><p class="muted">当前任务不可写入。未保存的草稿仍保留在这里，可复制后保存到其他位置。</p><el-input :model-value="noteText" type="textarea" :rows="4" readonly aria-label="未保存的记录草稿" /></div>
            <el-alert v-if="notesError" :title="`记录暂时无法加载：${notesError}`" type="error" :closable="false" />
            <el-alert v-else-if="filesError" title="附件列表暂时无法加载，记录中的图片和文件链接可能显示为已删除；请稍后重新打开。" type="warning" :closable="false" />
            <div v-else-if="!notes.length" class="empty">暂无记录<template v-if="canAct">，可写一份 Markdown 记录补充进展或处理结果。</template></div>
            <article v-for="note in notes" :key="note.id" class="task-note">
              <div class="note-meta">
                <span class="note-file">📄 {{ note.title || '进展记录.md' }}</span>
                <strong>{{ note.authorName }}</strong>
                <time>{{ formatDateTime(note.createdAt) }}</time>
                <el-button link type="primary" title="正文里的附件以系统内引用保存，下载的 .md 需回到系统查看附件" @click="downloadNote(note)">下载 .md</el-button>
                <el-button v-if="canDeleteNote(note)" link type="danger" :loading="deletingNote === note.id" :disabled="pending && deletingNote !== note.id" @click="removeNote(note)">删除</el-button>
              </div>
              <MarkdownView :content="note.content" :attachments="attachments" />
            </article>
          </el-tab-pane>
          <el-tab-pane label="附件" name="files" lazy><AttachmentPanel :key="`${task.id}-${filesVersion}`" owner-type="TASK" :owner-id="task.id" :can-manage="canAct" :archived="archived" :show-category-nav="false" :hint="isBug ? '上传复现截图、修复说明与验证资料；进展记录里粘贴的文件也会出现在这里。' : '历史任务的交付物与参考资料；进展记录里粘贴的文件也会出现在这里。'" /></el-tab-pane>
        </el-tabs>
        <div class="drawer-parent"><span class="muted">{{ isBug ? '关联功能' : '所属工作项' }}</span><el-button link type="primary" @click="openWorkItem">{{ origin?.name || node.name }} →</el-button></div>
        <TaskFormDialog v-model="editing" :node-id="node.id" :task="task" :members="members" :default-owner-id="node.ownerId" :work-items="relatedWorkItems" @saved="afterEdit" />
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.task-drawer-content{min-height:200px;padding:8px 6px 28px}
.drawer-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:22px}
.drawer-heading p{margin:0 0 9px;font-size:14px}.drawer-heading h2{margin:0;font-size:26px;overflow-wrap:anywhere}
.task-meta{display:flex;gap:18px;align-items:center;flex-wrap:wrap;font-size:14px;color:var(--muted);margin-bottom:20px}
.task-drawer-actions{display:flex;gap:10px;flex-wrap:wrap;margin:22px 0}
.task-drawer-tabs{margin-top:22px}.task-description{margin:8px 0 24px}.task-description dt{color:var(--muted);font-size:14px;margin:19px 0 6px}.task-description dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}
.note-composer{margin:8px 0 22px}.note-file-row{display:flex;align-items:center;gap:10px;margin-bottom:10px;flex-wrap:wrap}
.note-file-label{font-size:14px;color:var(--muted);flex:none}.note-file-row .el-input{flex:1 1 240px;max-width:380px}
.note-composer-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:10px}.note-composer-actions .muted{font-size:12px}
.task-note{padding:18px 0;border-bottom:1px solid var(--line)}.note-meta{display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:14px}.note-meta time{color:var(--muted)}
.note-file{color:var(--navy);font-weight:600;overflow-wrap:anywhere}.task-note .markdown-body{margin-top:10px}
.drawer-parent{border-top:1px solid var(--line);padding-top:20px;margin-top:24px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}.drawer-error p{line-height:1.7}
@media(max-width:600px){.drawer-heading h2{font-size:22px}.task-meta{gap:10px}.task-drawer-content{padding:0}}
</style>
