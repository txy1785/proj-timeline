<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, NodeLog, NodeStatus, Task, TaskKind, TaskStatus } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectStore } from '../stores/project'
import { canManageNode, canManageTask, isNodeClosed } from '../utils/permissions'
import { TASK_KIND_LABELS, TASK_KINDS, TASK_NEXT_ACTION, TASK_STATUSES, TASK_STATUS_LABELS, TASK_STATUS_TYPES } from '../utils/task'
import { NODE_STATUS_LABELS, NODE_STATUS_TYPES, WORK_ITEM_TYPE_LABELS } from '../utils/workItem'
import { formatDateTime } from '../utils/format'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import DeliverablePanel from '../components/DeliverablePanel.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'
import TaskFormDialog from '../components/TaskFormDialog.vue'
import TaskDrawer from '../components/TaskDrawer.vue'

const props = withDefaults(defineProps<{ initialView?: 'list' | 'kanban' }>(), { initialView: 'list' })
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const context = useProjectStore()
const id = computed(() => Number(route.params.id))
const node = ref<NodeItem>()
const loading = ref(true)
const error = ref('')
const activeTab = ref('tasks')
const kindFilter = ref<TaskKind | 'ALL'>('ALL')
const statusFilter = ref<TaskStatus | 'ALL' | 'OPEN'>('ALL')
const taskDialog = ref(false)
const editingTask = ref<Task | null>(null)
const workItemDialog = ref(false)
const drawerOpen = ref(false)
const drawerTaskId = ref(0)
const busyTaskId = ref(0)
const busyNode = ref(false)
const riskDialog = ref(false)
const riskMode = ref<'block' | 'overdue'>('block')
const riskReason = ref('')
const riskBaseline = ref('')
const savingRisk = ref(false)
const taskSection = ref<HTMLElement>()
let generation = 0

const project = computed(() => context.project?.id === node.value?.projectId ? context.project : undefined)
const members = computed(() => context.projectId === node.value?.projectId ? context.members : [])
const archived = computed(() => node.value?.projectStatus === 'ARCHIVED')
const closed = computed(() => isNodeClosed(node.value))
const manager = computed(() => !loading.value && !error.value && canManageNode(auth.user, members.value, node.value))
const canMutateNode = computed(() => manager.value && !closed.value)
const total = computed(() => node.value?.totalTasks ?? node.value?.tasks?.length ?? 0)
const done = computed(() => node.value?.doneTasks ?? node.value?.tasks?.filter(task => task.status === 'DONE').length ?? 0)
const remaining = computed(() => Math.max(0, total.value - done.value))
const projectWorkItems = computed(() => [...(project.value?.projectWorkItems || []), ...(project.value?.modules.flatMap(module => module.nodes || []) || [])])
const mode = computed(() => route.path.endsWith('/kanban') || props.initialView === 'kanban' ? 'kanban' : 'list')
const filteredTasks = computed(() => (node.value?.tasks || []).filter(task => (kindFilter.value === 'ALL' || (task.kind || 'FEATURE') === kindFilter.value)
  && (statusFilter.value === 'ALL' || (statusFilter.value === 'OPEN' ? task.status !== 'DONE' : task.status === statusFilter.value))))
const columns = computed(() => TASK_STATUSES.map(status => ({ status, label: TASK_STATUS_LABELS[status], tasks: filteredTasks.value.filter(task => task.status === status) })))
const logs = computed(() => (node.value?.logs || []) as (NodeLog & { detail?: string })[])

function syncDrawer() {
  const taskId = Number(route.query.task)
  drawerTaskId.value = Number.isSafeInteger(taskId) && taskId > 0 ? taskId : 0
  drawerOpen.value = drawerTaskId.value > 0
}
async function load(refreshContext = false) {
  const current = ++generation
  loading.value = true; error.value = ''
  try {
    const value = await api<NodeItem>('GET', '/nodes/' + id.value)
    if (current !== generation) return
    await context.load(value.projectId, refreshContext)
    if (current !== generation) return
    if (context.error || context.project?.id !== value.projectId) throw new Error(context.error || '项目上下文暂时不可用')
    node.value = value
    syncDrawer()
  } catch (e) {
    if (current === generation) { error.value = (e as Error).message; node.value = undefined }
  } finally { if (current === generation) loading.value = false }
}
watch(id, () => { node.value = undefined; activeTab.value = 'tasks'; void load() }, { immediate: true })
watch(() => route.query.task, syncDrawer)
watch(() => [route.query.kind, route.query.status], () => {
  const kind = String(route.query.kind || 'ALL') as TaskKind | 'ALL'
  const status = String(route.query.status || 'ALL') as TaskStatus | 'ALL' | 'OPEN'
  kindFilter.value = TASK_KINDS.includes(kind as TaskKind) ? kind : 'ALL'
  statusFilter.value = status === 'OPEN' || TASK_STATUSES.includes(status as TaskStatus) ? status : 'ALL'
}, { immediate: true })
watch(drawerOpen, open => {
  if (!open && route.query.task) {
    const query = { ...route.query }; delete query.task
    void router.replace({ query })
  }
})
onBeforeUnmount(() => { generation++ })

function updateFilters() {
  const query = { ...route.query }
  if (kindFilter.value === 'ALL') delete query.kind; else query.kind = kindFilter.value
  if (statusFilter.value === 'ALL') delete query.status; else query.status = statusFilter.value
  void router.replace({ query })
}
function openTask(task: Task) { void router.replace({ query: { ...route.query, task: String(task.id) } }) }
function goView(view: 'list' | 'kanban') { void router.push({ path: '/nodes/' + id.value + (view === 'kanban' ? '/kanban' : ''), query: route.query }) }
function createTask() { editingTask.value = null; taskDialog.value = true }
function editTask(task: Task) { editingTask.value = task; taskDialog.value = true }
function canChangeTask(task: Task) { return !closed.value && !loading.value && !error.value && canManageTask(auth.user, members.value, node.value, task) }
async function quickStatus(task: Task) {
  if (busyTaskId.value || busyNode.value || !canChangeTask(task)) return
  const action = TASK_NEXT_ACTION[task.status]
  busyTaskId.value = task.id
  try {
    await api<Task>('PUT', '/tasks/' + task.id + '/status', { status: action.next, expectedStatus: task.status })
    await load(true); ElMessage.success('任务已设为' + TASK_STATUS_LABELS[action.next])
  } catch (e) { ElMessage.error((e as Error).message); await load(true) }
  finally { busyTaskId.value = 0 }
}
async function removeTask(task: Task) {
  if (!canMutateNode.value || busyTaskId.value || busyNode.value) return
  try {
    await ElMessageBox.confirm('删除「' + task.name + '」后，其记录与历史工时会一并删除，附件会标记删除。此操作不能恢复。', '删除任务', {
      type: 'warning', confirmButtonText: '删除任务', cancelButtonText: '保留'
    })
  } catch { return }
  busyTaskId.value = task.id
  try { await api('DELETE', '/tasks/' + task.id); await load(true); ElMessage.success('任务已删除') }
  catch (e) { ElMessage.error((e as Error).message); await load(true) }
  finally { busyTaskId.value = 0 }
}
function taskCommand(command: string, task: Task) {
  if (command === 'edit') editTask(task)
  else if (command === 'delete') void removeTask(task)
}
async function setNodeStatus(status: NodeStatus) {
  if (!canMutateNode.value || busyNode.value || busyTaskId.value) return
  if (status === 'DONE' && remaining.value) { ElMessage.warning('还剩 ' + remaining.value + ' 个任务未完成'); return }
  if (status === 'CANCELLED') {
    try {
      await ElMessageBox.confirm('取消后保留工作项历史，未完成任务不再计入人员负载与周报计划人天；任务不能继续修改。', '取消工作项', {
        type: 'warning', confirmButtonText: '确认取消工作项', cancelButtonText: '保留工作项'
      })
    } catch { return }
  }
  busyNode.value = true
  try { await api('POST', '/nodes/' + id.value + '/status', { status }); await load(true); ElMessage.success('工作项已设为' + NODE_STATUS_LABELS[status]) }
  catch (e) { ElMessage.error((e as Error).message); await load(true) }
  finally { busyNode.value = false }
}
function openRisk(mode: 'block' | 'overdue') {
  if (!canMutateNode.value) return
  riskMode.value = mode
  riskReason.value = (mode === 'block' ? node.value?.blockedReason : node.value?.overdueReason) || ''
  riskBaseline.value = riskReason.value
  riskDialog.value = true
}
async function allowRiskClose(): Promise<boolean> {
  if (savingRisk.value) return false
  if (!riskDialog.value || riskReason.value === riskBaseline.value) return true
  try {
    await ElMessageBox.confirm('风险说明尚未保存，是否放弃本次输入？', '未保存的风险说明', { confirmButtonText: '放弃并关闭', cancelButtonText: '继续编辑' })
    return true
  } catch { return false }
}
async function closeRisk(done?: () => void) { if (await allowRiskClose()) { if (done) done(); else riskDialog.value = false } }
onBeforeRouteLeave(allowRiskClose)
async function saveRisk() {
  if (!canMutateNode.value || savingRisk.value) return
  if (riskMode.value === 'overdue' && !node.value?.flags.overdue) { ElMessage.warning('工作项已不再延期，未保存的说明仍保留在窗口中'); return }
  const reason = riskReason.value.trim()
  if (!reason) { ElMessage.warning(riskMode.value === 'block' ? '请填写具体受阻原因' : '请填写延期说明'); return }
  savingRisk.value = true
  try {
    if (riskMode.value === 'block') await api('POST', '/nodes/' + id.value + '/block', { reason })
    else await api('PUT', '/nodes/' + id.value + '/overdue-reason', { reason })
    riskBaseline.value = riskReason.value; riskDialog.value = false
    await load(true)
    ElMessage.success(riskMode.value === 'block' ? '受阻原因已保存' : '延期说明已更新，延期状态仍按计划日期计算')
  } catch (e) { ElMessage.error((e as Error).message); await load(true) }
  finally { savingRisk.value = false }
}
async function unblock() {
  if (!canMutateNode.value || busyNode.value) return
  try { await ElMessageBox.confirm('确认当前阻塞问题已经解决？解除后仍保留历史记录。', '解除受阻', { confirmButtonText: '确认解除', cancelButtonText: '仍未解决' }) }
  catch { return }
  busyNode.value = true
  try { await api('POST', '/nodes/' + id.value + '/unblock'); await load(true); ElMessage.success('已解除受阻') }
  catch (e) { ElMessage.error((e as Error).message); await load(true) }
  finally { busyNode.value = false }
}
function showOpenTasks() {
  activeTab.value = 'tasks'; statusFilter.value = 'OPEN'; kindFilter.value = 'ALL'; updateFilters()
  taskSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function logDetail(log: NodeLog & { detail?: string }) {
  let text = log.detail || log.summary || log.description || '工作项信息已更新'
  for (const [status, label] of Object.entries({ ...NODE_STATUS_LABELS, ...TASK_STATUS_LABELS })) text = text.replaceAll(status, label)
  return text
}
</script>

<template>
  <div v-loading="loading" class="node-page">
    <section v-if="error" class="surface node-error" role="alert"><h1>工作项暂时不可用</h1><p>{{ error }}</p><div class="page-actions"><el-button @click="load(true)">重新加载</el-button><el-button @click="router.push('/projects')">返回项目</el-button></div></section>
    <template v-else-if="node">
      <header class="page-head node-page-head">
        <div><el-breadcrumb separator="/"><el-breadcrumb-item :to="'/projects/' + node.projectId + '/overview'">{{ project?.name || '所属项目' }}</el-breadcrumb-item><el-breadcrumb-item :to="'/projects/' + node.projectId + '/work-items'">工作项</el-breadcrumb-item><el-breadcrumb-item>{{ node.name }}</el-breadcrumb-item></el-breadcrumb><div class="node-title"><h1>{{ node.name }}</h1><el-tag :type="NODE_STATUS_TYPES[node.status]">{{ NODE_STATUS_LABELS[node.status] }}</el-tag><el-tag v-if="node.flags.overdue" type="danger">已延期</el-tag><el-tag v-if="node.flags.blocked && !closed" type="warning">受阻</el-tag></div><p><RouterLink v-if="node.moduleId" :to="'/projects/' + node.projectId + '/modules/' + node.moduleId">{{ node.moduleName }}</RouterLink><span v-else>项目级工作项</span> · 负责人 {{ node.ownerName }} · 计划 {{ node.planStart }}—{{ node.planEnd }}</p><p v-if="node.actualStart || node.actualEnd" class="muted">实际开始 {{ node.actualStart || '未开始' }} · 实际完成 {{ node.actualEnd || '未完成' }}</p></div>
        <div class="page-actions"><el-button v-if="manager" @click="workItemDialog = true">编辑工作项</el-button><el-dropdown v-if="canMutateNode" :disabled="busyNode || !!busyTaskId" @command="setNodeStatus('CANCELLED')"><el-button :disabled="busyNode || !!busyTaskId">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="cancel">取消工作项</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
      </header>
      <el-alert v-if="archived" title="项目已归档，工作项、任务、记录与文件只读" type="info" :closable="false" class="node-state-alert" />
      <el-alert v-else-if="closed" :title="node.status === 'DONE' ? '工作项已完成，不能再新增、编辑或重新打开任务' : '工作项已取消，任务不再继续执行，历史内容仍可查看'" type="info" :closable="false" class="node-state-alert" />
      <section class="surface node-summary">
        <div class="node-progress"><strong>{{ total ? '已完成 ' + done + '/' + total + ' 个任务' : '暂无任务' }} · {{ node.progress }}%</strong><el-progress :percentage="node.progress" :show-text="false" :stroke-width="9" /><span class="muted">{{ total ? '进度由任务完成率自动计算' : '没有任务时按工作项状态估算进度' }}</span></div><span class="muted">{{ WORK_ITEM_TYPE_LABELS[node.type] }}</span>
        <p v-if="node.description" class="node-description">{{ node.description }}</p>
      </section>
      <section v-if="node.flags.overdue && !closed" class="node-risk node-risk-overdue" aria-label="延期说明"><div><strong>延期说明</strong><p>{{ node.overdueReason || '负责人尚未补充延期原因' }}</p><small>填写说明不会自动改期，延期仍按计划日期计算。</small></div><div v-if="canMutateNode" class="page-actions"><el-button type="primary" :disabled="busyNode" @click="openRisk('overdue')">更新说明</el-button><el-button :disabled="busyNode" @click="workItemDialog = true">调整计划</el-button></div></section>
      <section v-if="node.flags.blocked && !closed" class="node-risk node-risk-blocked" aria-label="受阻原因"><div><strong>受阻原因</strong><p>{{ node.blockedReason || '负责人尚未补充受阻原因' }}</p></div><div v-if="canMutateNode" class="page-actions"><el-button :disabled="busyNode" @click="openRisk('block')">更新原因</el-button><el-button :loading="busyNode" @click="unblock">解除受阻</el-button></div></section>
      <section ref="taskSection" class="surface node-content">
        <el-tabs v-model="activeTab">
          <el-tab-pane :label="'任务 ' + total" name="tasks">
            <div class="node-task-toolbar"><div><h2>执行任务</h2><p class="muted">点击任务名称，查看说明、记录进展或处理附件。</p></div><div class="page-actions"><el-button v-if="canMutateNode" type="primary" :disabled="busyNode || !!busyTaskId" @click="createTask">新建任务</el-button><el-button-group><el-button :type="mode === 'list' ? 'primary' : 'default'" :plain="mode === 'list'" @click="goView('list')">列表</el-button><el-button :type="mode === 'kanban' ? 'primary' : 'default'" :plain="mode === 'kanban'" @click="goView('kanban')">看板</el-button></el-button-group></div></div>
            <div class="node-task-filters"><el-select v-model="statusFilter" aria-label="任务状态筛选" @change="updateFilters"><el-option value="ALL" label="全部状态"/><el-option value="OPEN" label="未完成任务" /><el-option v-for="status in TASK_STATUSES" :key="status" :value="status" :label="TASK_STATUS_LABELS[status]" /></el-select><el-select v-model="kindFilter" aria-label="任务类型筛选" @change="updateFilters"><el-option value="ALL" label="全部类型" /><el-option v-for="kind in TASK_KINDS" :key="kind" :value="kind" :label="TASK_KIND_LABELS[kind]" /></el-select><el-button v-if="kindFilter !== 'ALL' || statusFilter !== 'ALL'" text @click="kindFilter = 'ALL'; statusFilter = 'ALL'; updateFilters()">清除筛选</el-button></div>
            <template v-if="mode === 'list'">
              <el-table :data="filteredTasks" class="node-task-table" :empty-text="total ? '当前筛选下没有任务' : '还没有任务，可将这次交付拆成具体任务'">
                <el-table-column label="任务" min-width="190"><template #default="scope"><el-button link type="primary" class="task-name" @click="openTask(scope.row)">{{ scope.row.name }}</el-button><small v-if="scope.row.noteCount" class="task-note-count">{{ scope.row.noteCount }} 条记录</small></template></el-table-column>
                <el-table-column prop="assigneeName" label="执行人" width="100" />
                <el-table-column label="截止日期" width="120"><template #default="scope">{{ scope.row.dueDate || '未设置' }}</template></el-table-column>
                <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="TASK_STATUS_TYPES[scope.row.status as TaskStatus]" size="small">{{ TASK_STATUS_LABELS[scope.row.status as TaskStatus] }}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="170" fixed="right"><template #default="scope"><el-button v-if="canChangeTask(scope.row)" link type="primary" :loading="busyTaskId === scope.row.id" :disabled="!!busyTaskId || busyNode" @click="quickStatus(scope.row)">{{ TASK_NEXT_ACTION[scope.row.status as TaskStatus].label }}</el-button><el-dropdown v-if="canMutateNode" :disabled="!!busyTaskId || busyNode" @command="taskCommand($event, scope.row)"><el-button text :disabled="!!busyTaskId || busyNode">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="edit">编辑任务</el-dropdown-item><el-dropdown-item command="delete">删除任务</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
              </el-table>
              <div class="node-task-cards"><article v-for="task in filteredTasks" :key="task.id" class="node-task-card"><el-button link type="primary" class="task-name" @click="openTask(task)">{{ task.name }}</el-button><p class="muted">{{ task.assigneeName }} · 截止 {{ task.dueDate || '未设置' }}</p><div class="mobile-task-actions"><el-tag :type="TASK_STATUS_TYPES[task.status]" size="small">{{ TASK_STATUS_LABELS[task.status] }}</el-tag><el-button v-if="canChangeTask(task)" link type="primary" :loading="busyTaskId === task.id" :disabled="!!busyTaskId || busyNode" @click="quickStatus(task)">{{ TASK_NEXT_ACTION[task.status].label }}</el-button><el-dropdown v-if="canMutateNode" :disabled="!!busyTaskId || busyNode" @command="taskCommand($event, task)"><el-button text :disabled="!!busyTaskId || busyNode">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="edit">编辑任务</el-dropdown-item><el-dropdown-item command="delete">删除任务</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></article><div v-if="!filteredTasks.length" class="empty">{{ total ? '当前筛选下没有任务' : '还没有任务，可将这次交付拆成具体任务' }}</div></div>
            </template>
            <div v-else class="node-kanban"><section v-for="column in columns" :key="column.status" class="node-kanban-column"><header><strong>{{ column.label }}</strong><el-tag size="small" type="info">{{ column.tasks.length }}</el-tag></header><article v-for="task in column.tasks" :key="task.id" class="node-kanban-card"><el-button link type="primary" class="task-name" @click="openTask(task)">{{ task.name }}</el-button><p class="muted">{{ task.assigneeName }} · {{ task.dueDate || '未设置截止日' }}</p><p v-if="task.noteCount" class="muted">{{ task.noteCount }} 条进展记录</p><el-button v-if="canChangeTask(task)" size="small" :loading="busyTaskId === task.id" :disabled="!!busyTaskId || busyNode" @click="quickStatus(task)">{{ TASK_NEXT_ACTION[task.status].label }}</el-button></article><div v-if="!column.tasks.length" class="empty">暂无任务</div></section></div>
            <div class="node-completion"><div><template v-if="closed"><span>{{ NODE_STATUS_LABELS[node.status] }} · 历史任务仍可查看</span></template><template v-else-if="remaining"><button class="remaining-link" @click="showOpenTasks">还剩 {{ remaining }} 个任务未完成</button></template><span v-else>{{ total ? '任务已做完，可完成工作项' : '可以直接开始工作项，或先拆分任务' }}</span><small>交付物已提供 {{ node.deliverableProvided || 0 }}/{{ node.deliverableTotal || 0 }}，不影响工作项完成</small></div><div v-if="canMutateNode" class="page-actions"><el-button v-if="node.status === 'NOT_STARTED'" type="primary" :loading="busyNode" :disabled="!!busyTaskId" @click="setNodeStatus('IN_PROGRESS')">开始工作项</el-button><el-button v-else-if="node.status === 'IN_PROGRESS'" type="primary" :loading="busyNode" :disabled="remaining > 0 || !!busyTaskId" @click="setNodeStatus('DONE')">完成工作项</el-button><el-button v-if="!node.flags.blocked" text :disabled="busyNode || !!busyTaskId" @click="openRisk('block')">标记受阻</el-button></div></div>
          </el-tab-pane>
          <el-tab-pane :label="'交付物 ' + (node.deliverableProvided || 0) + '/' + (node.deliverableTotal || 0)" name="deliverables" lazy><DeliverablePanel :node-id="id" :items="node.deliverables || []" :members="members" :can-manage="manager" :node-closed="closed" :archived="archived" @refresh="load(true)" /></el-tab-pane>
          <el-tab-pane label="文件" name="files" lazy><AttachmentPanel :key="id" owner-type="NODE" :owner-id="id" :can-manage="manager" :archived="archived" :show-category-nav="false" hint="本工作项的设计说明、流程图、自测记录与交付物；任务附件在各任务详情中查看。" /></el-tab-pane>
          <el-tab-pane :label="'变更记录 ' + logs.length" name="logs"><div v-if="!logs.length" class="empty">暂无变更记录</div><article v-for="log in logs" :key="log.id" class="node-log"><strong>{{ logDetail(log) }}</strong><p class="muted">{{ log.userName || '系统' }} · {{ formatDateTime(log.createdAt) }}</p></article></el-tab-pane>
        </el-tabs>
      </section>
      <TaskFormDialog v-model="taskDialog" :node-id="id" :task="editingTask" :members="members" :default-owner-id="node.ownerId" :work-items="projectWorkItems" @saved="load(true)" />
      <WorkItemFormDialog v-model="workItemDialog" :project-id="node.projectId" :work-item="node" :members="members" @saved="load(true)" />
      <el-dialog :model-value="riskDialog" :title="riskMode === 'block' ? '标记工作项受阻' : '更新延期说明'" width="min(560px, calc(100vw - 28px))" :close-on-click-modal="false" :before-close="closeRisk" @update:model-value="riskDialog = $event"><el-form label-position="top" :disabled="savingRisk"><el-form-item :label="riskMode === 'block' ? '受阻原因' : '延期说明'" required><el-input v-model="riskReason" type="textarea" :rows="5" :placeholder="riskMode === 'block' ? '例如：等待支付资质审批，需要项目经理协调' : '说明延期原因、当前进展和下一步安排'" /></el-form-item><p v-if="riskMode === 'overdue'" class="muted">更新说明不会自动调整计划日期，也不会直接解除延期。</p></el-form><template #footer><el-button :disabled="savingRisk" @click="closeRisk()">取消</el-button><el-button type="primary" :loading="savingRisk" :disabled="!canMutateNode || (riskMode === 'overdue' && !node.flags.overdue)" @click="saveRisk">{{ riskMode === 'block' ? '保存并标记受阻' : '保存说明' }}</el-button></template></el-dialog>
    </template>
    <div v-else-if="loading" class="surface empty">正在加载工作项…</div>
    <TaskDrawer v-model="drawerOpen" :node-id="id" :task-id="drawerTaskId" @updated="load(true)" />
  </div>
</template>

<style scoped>
.node-page{min-height:240px}.node-page-head{align-items:flex-start}.node-title{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0 8px}.node-title h1{margin:0;overflow-wrap:anywhere}.node-page-head p{line-height:1.7}.node-state-alert{margin-bottom:18px}
.node-summary{margin-bottom:18px;display:flex;gap:24px;align-items:center;flex-wrap:wrap}.node-progress{display:flex;align-items:center;gap:18px;flex-wrap:wrap;flex:1}.node-progress .el-progress{width:210px;max-width:100%}.node-progress .muted{font-size:13px}.node-description{width:100%;margin:0;white-space:pre-wrap;line-height:1.7}
.node-risk{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 22px;border:1px solid var(--line);border-radius:10px;margin-bottom:18px}.node-risk-overdue{background:#fff5f5;border-color:#f6d6da}.node-risk-blocked{background:#f7f3fd;border-color:#e2d7f3}.node-risk strong{font-size:16px}.node-risk p{margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.65}.node-risk small{color:var(--muted)}
.node-task-toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin:12px 0 18px}.node-task-toolbar h2{margin:0 0 8px}.node-task-toolbar p{font-size:14px;margin:0}.node-task-filters{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:16px}.node-task-filters .el-select{width:145px}.task-name{font-size:14px;height:auto;line-height:1.65;white-space:normal;text-align:left;justify-content:flex-start}.task-name :deep(span){white-space:normal;overflow-wrap:anywhere}.task-note-count{display:block;color:var(--muted);margin-top:4px}
.node-task-cards{display:none}.node-task-card{border:1px solid var(--line);border-radius:9px;padding:16px}.node-task-card p{font-size:13px}.mobile-task-actions{display:flex;align-items:center;gap:14px;flex-wrap:wrap}
.node-kanban{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.node-kanban-column{padding:15px;background:#f1f4f9;border-radius:10px;min-height:200px}.node-kanban-column>header{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:14px}.node-kanban-card{background:white;border:1px solid var(--line);border-radius:8px;padding:16px;margin-top:12px}.node-kanban-card p{font-size:13px;margin:9px 0;line-height:1.6}.node-kanban-column .empty{padding:25px 10px;font-size:14px}
.node-completion{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:24px;border-top:1px solid var(--line);padding-top:20px}.node-completion small{display:block;color:var(--muted);font-size:13px;margin-top:8px}.remaining-link{padding:0;color:var(--navy);border:0;background:transparent;font:inherit;cursor:pointer;text-align:left}.remaining-link:hover{color:var(--blue)}.node-log{padding:18px 0;border-bottom:1px solid var(--line)}.node-log strong{font-size:14px;font-weight:500}.node-log p{margin:8px 0 0;font-size:13px}.node-error h1{font-size:24px}.node-error p{line-height:1.7}
@media(max-width:1000px){.node-task-toolbar,.node-completion,.node-risk{align-items:flex-start;flex-direction:column}.node-progress{width:100%}.node-page-head{flex-wrap:wrap}.node-kanban{gap:10px}.node-kanban-column{padding:12px}}
@media(max-width:700px){.node-task-table{display:none}.node-task-cards{display:grid;gap:12px}.node-kanban{grid-template-columns:1fr}.node-summary{padding:18px}.node-progress{gap:12px}.node-progress .el-progress{width:100%}.node-risk{padding:18px}.node-task-filters .el-select{flex:1;min-width:120px}.node-title h1{font-size:24px}}
</style>
