<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { Attachment, NodeItem, NoteRecord, Task, TaskStatus, Timeline, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectStore } from '../stores/project'
import { canManageNode, canManageTask, canChangeTaskStatus, canEditTask, taskWorkItemContext, isNodeClosed } from '../utils/permissions'
import { TASK_KIND_LABELS, TASK_NEXT_ACTION, TASK_STATUS_LABELS, TASK_STATUS_TYPES } from '../utils/task'
import { BUG_NEXT_ACTION, BUG_STATUS_LABELS } from '../utils/bug'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import NoteRecords from '../components/NoteRecords.vue'
import TaskFormDialog from '../components/TaskFormDialog.vue'

const route = useRoute()
const auth = useAuthStore()
const context = useProjectStore()
const router = useRouter()
const nodeId = computed(() => Number(route.params.nodeId))
const taskId = computed(() => Number(route.params.taskId))
const projectName = computed(() => context.project?.name || node.value?.projectName || '项目')

const node = ref<NodeItem>()
const task = ref<Task>()
const members = ref<User[]>([])
const records = ref<InstanceType<typeof NoteRecords>>()
const noteCount = ref(0)
const attachments = ref<Attachment[]>([])
const filesError = ref('')
const filesVersion = ref(0)
const loading = ref(false)
const error = ref('')
const savingStatus = ref(false)
const activeTab = ref('description')
const editing = ref(false)
const relatedWorkItems = ref<NodeItem[]>([])
let generation = 0
const pending = computed(() => savingStatus.value || !!records.value?.pending)
const hasDraft = computed(() => !!records.value?.hasDraft)
const draftText = computed(() => records.value?.draft || '')
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
  const currentNodeId = nodeId.value
  const currentTaskId = taskId.value
  loading.value = true; error.value = ''
  try {
    if (!Number.isSafeInteger(currentNodeId) || currentNodeId <= 0 || !Number.isSafeInteger(currentTaskId) || currentTaskId <= 0) throw new Error('任务链接无效')
    const value = await api<NodeItem>('GET', `/nodes/${currentNodeId}`)
    const found = value.tasks?.find(item => item.id === currentTaskId && item.nodeId === currentNodeId)
      || value.bugs?.find(item => item.id === currentTaskId && item.nodeId === currentNodeId)
    if (!found) throw new Error('任务已删除或不属于这个工作项')
    const [people, files] = await Promise.allSettled([
      api<User[]>('GET', `/projects/${value.projectId}/members`),
      api<Attachment[]>('GET', '/attachments', undefined, { ownerType: 'TASK', ownerId: currentTaskId })
    ])
    if (current !== generation) return
    if (people.status === 'rejected') throw people.reason
    node.value = value; task.value = found; members.value = people.value
    if (files.status === 'fulfilled') { attachments.value = files.value; filesError.value = '' }
    else { attachments.value = []; filesError.value = (files.reason as Error).message }
  } catch (e) {
    if (current === generation) { error.value = (e as Error).message; task.value = undefined; node.value = undefined; members.value = [] }
  } finally { if (current === generation) loading.value = false }
}

watch([nodeId, taskId], ([currentNodeId, currentTaskId], old) => {
  if (old && currentNodeId === old[0] && currentTaskId === old[1]) return
  node.value = undefined; task.value = undefined; noteCount.value = 0
  attachments.value = []; filesError.value = ''; filesVersion.value = 0
  activeTab.value = 'description'; relatedWorkItems.value = []
  void load()
}, { immediate: true })

function onUploaded(file: Attachment) {
  attachments.value = [file, ...attachments.value]
  filesVersion.value++
}

function onAttachmentRemoved(attachmentId: number) {
  attachments.value = attachments.value.filter(item => item.id !== attachmentId)
  filesVersion.value++
}

async function allowLeave(): Promise<boolean> {
  if (savingStatus.value || editing.value) { ElMessage.info('请先完成当前操作'); return false }
  return records.value ? await records.value.allowLeave() : true
}

async function requestClose() {
  if (!await allowLeave()) return
  records.value?.discard()
  await returnToWorkItem()
}

async function returnToWorkItem() {
  const target = origin.value?.id || node.value?.id || nodeId.value
  if (target) await router.push({ path: `/nodes/${target}`, query: { tab: isBug.value ? 'bugs' : 'history' } })
  else await router.push('/projects')
}

onBeforeRouteLeave(async () => await allowLeave())
onBeforeRouteUpdate(async (to, from) => (to.params.taskId === from.params.taskId && to.params.nodeId === from.params.nodeId) || await allowLeave())
function beforeUnload(event: BeforeUnloadEvent) {
  if (hasDraft.value) { event.preventDefault(); event.returnValue = '' }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => { generation++; window.removeEventListener('beforeunload', beforeUnload) })

async function changeStatus(status: TaskStatus) {
  const current = task.value
  if (!current || !canChangeStatus.value || pending.value || status === current.status) return
  savingStatus.value = true
  try {
    await api<Task>('PUT', `/tasks/${current.id}/status`, { status, expectedStatus: current.status })
    await load()
    ElMessage.success(isBug.value ? `Bug已设为${BUG_STATUS_LABELS[status]}` : `任务已设为${TASK_STATUS_LABELS[status]}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
    await load()
  } finally { savingStatus.value = false }
}

function canManageNote(note: NoteRecord) {
  return !loading.value && !error.value && node.value?.projectStatus === 'ACTIVE' && !!auth.user
    && auth.user.active !== false && !auth.isViewer
    && (canManageNode(auth.user, members.value, origin.value) || note.authorId === auth.user.id)
}

async function openEdit() {
  if (!canEdit.value || !node.value || pending.value) return
  try {
    const timeline = await api<Timeline>('GET', `/projects/${node.value.projectId}/timeline`)
    relatedWorkItems.value = [...timeline.modules.flatMap(module => module.nodes || []), ...(timeline.projectWorkItems || [])]
    editing.value = true
  } catch (e) { ElMessage.error((e as Error).message) }
}
async function afterEdit() { await load() }
async function openWorkItem() {
  if (!node.value || !await allowLeave()) return
  records.value?.discard()
  await router.push({ path: `/nodes/${origin.value?.id || node.value.id}`, query: { tab: isBug.value ? 'bugs' : 'history' } })
}
</script>

<template>
  <section v-loading="loading" class="surface task-detail-page">
    <div class="task-detail-content">
      <el-breadcrumb v-if="node" separator="/" class="task-detail-crumb">
        <el-breadcrumb-item :to="`/projects/${node.projectId}/overview`">{{ projectName }}</el-breadcrumb-item>
        <el-breadcrumb-item :to="`/nodes/${origin?.id || node.id}`">{{ origin?.name || node.name }}</el-breadcrumb-item>
        <el-breadcrumb-item>{{ task?.name || '任务详情' }}</el-breadcrumb-item>
      </el-breadcrumb>
      <header class="page-head task-detail-heading">
        <div>
          <p class="muted">{{ isBug ? 'Bug · 独立跟踪修复' : '历史任务' }}</p>
          <h1>{{ task?.name || (loading ? '正在加载详情' : '详情') }}</h1>
        </div>
        <div class="page-actions">
          <el-button :disabled="pending" @click="requestClose()">返回工作项</el-button>
        </div>
      </header>
      <div v-if="error" class="task-detail-error" role="alert"><el-alert :title="error" type="error" :closable="false" /><p class="muted">任务可能已删除，或你的读取权限已变化。</p><template v-if="draftText.trim()"><p class="muted">未保存的记录草稿，可复制后保留：</p><el-input :model-value="draftText" type="textarea" :rows="4" readonly aria-label="未保存的记录草稿" /></template><el-button @click="load">重新加载</el-button><el-button @click="requestClose()">返回工作项</el-button></div>
      <template v-else-if="task && node">
        <div class="task-meta"><el-tag :type="TASK_STATUS_TYPES[task.status]">{{ stateLabel }}</el-tag><span>{{ isBug ? '负责人' : '执行人' }} {{ task.assigneeName }}</span><span>截止 {{ task.dueDate || '未设置' }}</span></div>
        <el-alert v-if="archived" title="项目已归档，任务、记录与附件只读" type="info" :closable="false" />
        <el-alert v-else-if="isBug && origin?.status === 'DONE'" title="关联功能已交付，此Bug仍可继续修复；原交付记录保持不变。" type="info" :closable="false" /><el-alert v-else-if="origin?.status === 'CANCELLED' || (!isBug && isNodeClosed(node))" title="关联工作项已关闭，内容和状态不可修改；记录与附件仍按原有权限处理。" type="info" :closable="false" />
        <div v-if="canChangeStatus || canEdit" class="task-detail-actions">
          <el-button v-if="canChangeStatus && nextAction" type="primary" :loading="savingStatus" :disabled="pending && !savingStatus" @click="changeStatus(nextAction.next)">{{ isBug ? nextAction.label : task.status === 'DOING' ? '标记完成' : task.status === 'TODO' ? '开始任务' : '重新开始' }}</el-button>
          <el-button v-if="canEdit" :disabled="pending" @click="openEdit">{{ isBug ? '编辑Bug' : '编辑任务' }}</el-button>
          <el-dropdown v-if="canChangeStatus && task.status !== 'TODO'" :disabled="pending" @command="changeStatus('TODO')"><el-button :disabled="pending">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="TODO">{{ isBug ? '设为待修复' : '设为待完成' }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
        <el-tabs v-model="activeTab" class="task-detail-tabs">
          <el-tab-pane label="说明" name="description">
            <dl class="task-description"><dt>类型</dt><dd>{{ isBug ? 'Bug' : TASK_KIND_LABELS[task.kind || 'FEATURE'] }}</dd><template v-if="isBug || task.relatedNodeName"><dt>关联功能</dt><dd>{{ origin?.name || task.relatedNodeName }}</dd></template><dt>描述</dt><dd>{{ task.description || '暂无描述，可在进展记录中补充执行情况。' }}</dd><template v-if="task.situation"><dt>{{ isBug ? '问题背景' : '任务情况' }}</dt><dd>{{ task.situation }}</dd></template><template v-if="task.handling"><dt>处理方式</dt><dd>{{ task.handling }}</dd></template><template v-if="!isBug"><dt>历史计划人天</dt><dd>{{ task.estimateDays ?? '尚未估算' }}</dd></template></dl>
          </el-tab-pane>
          <el-tab-pane :label="`进展记录 ${noteCount}`" name="records">
            <el-alert v-if="filesError" class="files-warning" title="附件列表暂时无法加载，记录中的图片和文件链接可能显示为已删除；请稍后重新打开。" type="warning" :closable="false" />
            <NoteRecords ref="records" owner-type="TASK" :owner-id="task.id" :list-path="`/tasks/${task.id}/notes`" item-path="/task-notes" category="TASK_RECORD" :title-prefix="isBug ? '修复记录' : '进展记录'" :attachments="attachments" :can-write="canAct" :can-upload="canAct && !archived" :can-manage="canManageNote" read-only-hint="当前任务不可写入。" @uploaded="onUploaded" @removed="onAttachmentRemoved" @count="noteCount = $event" />
          </el-tab-pane>
          <el-tab-pane label="附件" name="files" lazy><AttachmentPanel :key="`${task.id}-${filesVersion}`" owner-type="TASK" :owner-id="task.id" :can-manage="canAct" :archived="archived" :show-category-nav="false" :hint="isBug ? '上传复现截图、修复说明与验证资料；进展记录里粘贴的文件也会出现在这里。' : '历史任务的交付物与参考资料；进展记录里粘贴的文件也会出现在这里。'" /></el-tab-pane>
        </el-tabs>
        <div class="task-detail-parent"><span class="muted">{{ isBug ? '关联功能' : '所属工作项' }}</span><el-button link type="primary" @click="openWorkItem">{{ origin?.name || node.name }} →</el-button></div>
        <TaskFormDialog v-model="editing" :node-id="node.id" :task="task" :members="members" :default-owner-id="node.ownerId" :work-items="relatedWorkItems" @saved="afterEdit" />
      </template>
    </div>
  </section>
</template>

<style scoped>
.task-detail-page{max-width:1100px;margin:0 auto}
.task-detail-content{min-height:260px;padding:2px 2px 12px}
.task-detail-crumb{margin-bottom:14px}
.task-detail-heading{align-items:flex-start;margin-bottom:8px}
.task-detail-heading p{margin:0 0 6px;font-size:14px}.task-detail-heading h1{margin:0 0 4px;font-size:28px;overflow-wrap:anywhere}
.task-meta{display:flex;gap:18px;align-items:center;flex-wrap:wrap;font-size:14px;color:var(--muted);margin:18px 0 20px}
.task-detail-actions{display:flex;gap:10px;flex-wrap:wrap;margin:22px 0}
.task-detail-tabs{margin-top:22px}.task-description{margin:8px 0 24px}.task-description dt{color:var(--muted);font-size:14px;margin:19px 0 6px}.task-description dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}
.files-warning{margin-bottom:12px}
.task-detail-parent{border-top:1px solid var(--line);padding-top:20px;margin-top:24px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}.task-detail-error p{line-height:1.7}
@media(max-width:600px){.task-detail-heading h1{font-size:22px}.task-meta{gap:10px}.task-detail-content{padding:0}.task-detail-heading{flex-direction:column}.task-detail-heading .page-actions{width:100%}}
</style>
