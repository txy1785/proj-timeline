<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, NodeLog, NodeStatus, Task, TaskStatus } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectStore } from '../stores/project'
import { canManageNode, canChangeTaskStatus, isNodeClosed } from '../utils/permissions'
import { TASK_NEXT_ACTION, TASK_STATUSES, TASK_STATUS_LABELS, TASK_STATUS_TYPES } from '../utils/task'
import { NODE_STATUS_LABELS, NODE_STATUS_TYPES, WORK_ITEM_TYPE_LABELS } from '../utils/workItem'
import { BUG_NEXT_ACTION, BUG_STATUS_LABELS } from '../utils/bug'
import { formatDateTime } from '../utils/format'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import DeliverablePanel from '../components/DeliverablePanel.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'
import BugReportDialog from '../components/BugReportDialog.vue'

const props = withDefaults(defineProps<{ initialView?: 'list' | 'kanban' }>(), { initialView: 'list' })
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const context = useProjectStore()
const id = computed(() => Number(route.params.id))
const node = ref<NodeItem>()
const loading = ref(true)
const error = ref('')
const activeTab = ref('description')
const bugDialog = ref(false)
const bugStatus = ref<TaskStatus | 'ALL'>('ALL')
const statusFilter = ref<TaskStatus | 'ALL' | 'OPEN'>('ALL')
const workItemDialog = ref(false)
const busyTaskId = ref(0)
const busyNode = ref(false)
const riskDialog = ref(false)
const riskMode = ref<'block' | 'overdue'>('block')
const riskReason = ref('')
const riskBaseline = ref('')
const savingRisk = ref(false)
let generation = 0

const project = computed(() => context.project?.id === node.value?.projectId ? context.project : undefined)
const members = computed(() => context.projectId === node.value?.projectId ? context.members : [])
const archived = computed(() => node.value?.projectStatus === 'ARCHIVED')
const closed = computed(() => isNodeClosed(node.value))
const manager = computed(() => !loading.value && !error.value && canManageNode(auth.user, members.value, node.value))
const canMutateNode = computed(() => manager.value && !closed.value)
const bugs = computed(() => node.value?.bugs || (node.value?.tasks || []).filter(task => task.kind === 'BUG'))
const openBugs = computed(() => bugs.value.filter(bug => bug.status !== 'DONE').length)
const legacyTasks = computed(() => (node.value?.tasks || []).filter(task => task.kind !== 'BUG'))
const mode = computed(() => route.path.endsWith('/kanban') || props.initialView === 'kanban' ? 'kanban' : 'list')
const filteredTasks = computed(() => legacyTasks.value.filter(task => statusFilter.value === 'ALL' || (statusFilter.value === 'OPEN' ? task.status !== 'DONE' : task.status === statusFilter.value)))
const filteredBugs = computed(() => bugs.value.filter(task => bugStatus.value === 'ALL' || task.status === bugStatus.value))
const columns = computed(() => TASK_STATUSES.map(status => ({ status, label: TASK_STATUS_LABELS[status], tasks: filteredTasks.value.filter(task => task.status === status) })))
const canReportBug = computed(() => !loading.value && !error.value && !auth.isViewer && node.value?.projectStatus === 'ACTIVE' && node.value.status !== 'CANCELLED')
const logs = computed(() => (node.value?.logs || []) as (NodeLog & { detail?: string })[])

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
    const legacyTaskId = Number(route.query.task)
    if (Number.isSafeInteger(legacyTaskId) && legacyTaskId > 0) {
      const selected = [...(value.tasks || []), ...(value.bugs || [])].find(task => task.id === legacyTaskId)
      const query = { ...route.query }; delete query.task
      await router.replace({ path: `/nodes/${selected?.nodeId || value.id}/tasks/${legacyTaskId}`, query })
      return
    }
    if (mode.value === 'kanban' && activeTab.value === 'description') activeTab.value = legacyTasks.value.length ? 'history' : 'bugs'
  } catch (e) {
    if (current === generation) { error.value = (e as Error).message; node.value = undefined }
  } finally { if (current === generation) loading.value = false }
}
watch(id, () => { node.value = undefined; activeTab.value = 'description'; void load() }, { immediate: true })
watch(() => [route.query.tab, route.query.kind, route.query.status], () => {
  if (['description', 'materials', 'bugs', 'activity', 'history'].includes(String(route.query.tab))) activeTab.value = String(route.query.tab)
  else if (route.query.kind === 'BUG') activeTab.value = 'bugs'
  const status = String(route.query.status || 'ALL') as TaskStatus | 'ALL' | 'OPEN'
  statusFilter.value = status === 'OPEN' || TASK_STATUSES.includes(status as TaskStatus) ? status : 'ALL'
}, { immediate: true })
onBeforeUnmount(() => { generation++ })

function updateFilters() {
  const query = { ...route.query }
  if (statusFilter.value === 'ALL') delete query.status; else query.status = statusFilter.value
  void router.replace({ query })
}
function openTask(task: Task) { void router.push(`/nodes/${task.nodeId || id.value}/tasks/${task.id}`) }
function goView(view: 'list' | 'kanban') { void router.push({ path: '/nodes/' + id.value + (view === 'kanban' ? '/kanban' : ''), query: { ...route.query, tab: 'history' } }) }
function canChangeTask(task: Task) { return !loading.value && !error.value && canChangeTaskStatus(auth.user, members.value, node.value, task) }
async function quickStatus(task: Task) {
  if (busyTaskId.value || busyNode.value || !canChangeTask(task)) return
  const action = task.kind === 'BUG' ? BUG_NEXT_ACTION[task.status] : TASK_NEXT_ACTION[task.status]
  busyTaskId.value = task.id
  try {
    await api<Task>('PUT', '/tasks/' + task.id + '/status', { status: action.next, expectedStatus: task.status })
    await load(true); ElMessage.success(task.kind === 'BUG' ? 'Bug已设为' + BUG_STATUS_LABELS[action.next] : '历史任务已设为' + TASK_STATUS_LABELS[action.next])
  } catch (e) { ElMessage.error((e as Error).message); await load(true) }
  finally { busyTaskId.value = 0 }
}
async function setNodeStatus(status: NodeStatus) {
  if (!canMutateNode.value || busyNode.value || busyTaskId.value) return
  if (status === 'DONE') {
    const warning = openBugs.value ? `当前仍有 ${openBugs.value} 个未解决Bug，可在交付后继续跟踪。` : ''
    try { await ElMessageBox.confirm('请确认功能达到约定的交付要求，相关资料已按需要提供。' + warning, '确认交付', { type: 'info', confirmButtonText: '确认已交付', cancelButtonText: '继续检查' }) }
    catch { return }
  }
  if (status === 'CANCELLED') {
    try {
      await ElMessageBox.confirm('取消后保留功能、资料与历史记录；此功能不再接收新的Bug。', '取消工作项', {
        type: 'warning', confirmButtonText: '确认取消工作项', cancelButtonText: '保留工作项'
      })
    } catch { return }
  }
  busyNode.value = true
  try { await api('POST', '/nodes/' + id.value + '/status', { status, expectedStatus: node.value?.status }); await load(true); ElMessage.success('工作项已设为' + NODE_STATUS_LABELS[status]) }
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
        <div><el-breadcrumb separator="/"><el-breadcrumb-item :to="'/projects/' + node.projectId + '/overview'">{{ project?.name || '所属项目' }}</el-breadcrumb-item><el-breadcrumb-item :to="'/projects/' + node.projectId + '/work-items'">工作项</el-breadcrumb-item><el-breadcrumb-item>{{ node.name }}</el-breadcrumb-item></el-breadcrumb><div class="node-title"><h1>{{ node.name }}</h1><el-tag :type="NODE_STATUS_TYPES[node.status]">{{ node.status === 'DONE' ? '已交付' : NODE_STATUS_LABELS[node.status] }}</el-tag><el-tag v-if="node.flags.overdue" type="danger">已延期</el-tag><el-tag v-if="node.flags.blocked && !closed" type="warning">受阻</el-tag></div><p><RouterLink v-if="node.moduleId" :to="'/projects/' + node.projectId + '/modules/' + node.moduleId">{{ node.moduleName }}</RouterLink><span v-else>项目级工作项</span> · 负责人 {{ node.ownerName }} · 计划 {{ node.planStart }}—{{ node.planEnd }}</p></div>
        <div class="page-actions"><el-button v-if="canReportBug" @click="bugDialog = true">报告 Bug</el-button><el-button v-if="canMutateNode" @click="workItemDialog = true">编辑工作项</el-button><el-button v-if="canMutateNode && node.status === 'NOT_STARTED'" type="primary" :loading="busyNode" @click="setNodeStatus('IN_PROGRESS')">开始工作</el-button><el-button v-else-if="canMutateNode && node.status === 'IN_PROGRESS'" type="primary" :loading="busyNode" @click="setNodeStatus('DONE')">确认交付</el-button><el-dropdown v-if="canMutateNode" :disabled="busyNode || !!busyTaskId" @command="setNodeStatus('CANCELLED')"><el-button :disabled="busyNode || !!busyTaskId">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="cancel">取消工作项</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
      </header>
      <el-alert v-if="archived" title="项目已归档，功能、Bug、资料与历史记录只读" type="info" :closable="false" />
      <el-alert v-else-if="node.status === 'DONE'" title="功能已交付，后续问题可继续报告和修复，无需重开功能。" type="success" :closable="false" />
      <el-alert v-else-if="node.status === 'CANCELLED'" title="工作项已取消，保留资料与历史，不再接收新Bug。" type="info" :closable="false" />
      <section class="surface node-facts"><span>实际开始 <strong>{{ node.actualStart || '未开始' }}</strong></span><span>交付日期 <strong>{{ node.actualEnd || '尚未交付' }}</strong></span><button @click="activeTab = 'materials'">交付要求 {{ node.deliverableProvided || 0 }}/{{ node.deliverableTotal || 0 }} 已提供</button><button @click="activeTab = 'bugs'">待处理 Bug {{ openBugs }}</button></section>
      <section v-if="node.flags.overdue" class="node-risk node-risk-overdue"><div><strong>交付已延期</strong><p>{{ node.overdueReason || '负责人尚未填写延期说明' }}</p><small>计划结束 {{ node.planEnd }}；说明不会自动延长计划日期。</small></div><div v-if="canMutateNode" class="page-actions"><el-button @click="openRisk('overdue')">{{ node.overdueReason ? '更新延期说明' : '填写延期说明' }}</el-button><el-button @click="workItemDialog = true">调整计划</el-button></div></section>
      <section v-if="node.flags.blocked && !closed" class="node-risk node-risk-blocked"><div><strong>工作受阻</strong><p>{{ node.blockedReason || '尚未填写受阻原因' }}</p></div><div v-if="canMutateNode" class="page-actions"><el-button @click="openRisk('block')">更新原因</el-button><el-button @click="unblock">解除受阻</el-button></div></section>
      <section class="surface node-body">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="功能说明" name="description"><div class="feature-description"><h2>这项功能要交付什么</h2><p>{{ node.description || '尚未填写功能说明，可补充功能范围、预期效果和验收要求。' }}</p><p class="muted">{{ WORK_ITEM_TYPE_LABELS[node.type] }} · 由 {{ node.ownerName }} 负责交付</p><el-button v-if="canMutateNode" @click="workItemDialog = true">完善功能说明</el-button></div><div v-if="canMutateNode && !node.flags.blocked" class="section-actions"><el-button @click="openRisk('block')">标记受阻</el-button></div></el-tab-pane>
          <el-tab-pane label="交付资料" name="materials" lazy><DeliverablePanel :node-id="id" :items="node.deliverables || []" :members="members" :can-manage="manager" :node-closed="closed" :archived="archived" @refresh="load(true)" /><div class="material-files"><h2>图纸、文档与实际文件</h2><AttachmentPanel :key="id" owner-type="NODE" :owner-id="id" :can-manage="manager" :archived="archived" :show-category-nav="false" hint="上传本功能的图纸、接口说明、测试截图和其他资料；可在上方交付要求中关联文件。" /></div></el-tab-pane>
          <el-tab-pane :label="'Bug ' + bugs.length" name="bugs"><div class="section-heading"><div><h2>功能问题与修复</h2><p class="muted">已交付的功能仍可独立跟踪Bug，保留原交付记录。</p></div><el-button v-if="canReportBug" type="primary" @click="bugDialog = true">报告 Bug</el-button></div><el-select v-model="bugStatus" class="status-filter" aria-label="Bug状态筛选"><el-option label="全部Bug" value="ALL"/><el-option v-for="status in TASK_STATUSES" :key="status" :label="BUG_STATUS_LABELS[status]" :value="status" /></el-select><div v-if="!filteredBugs.length" class="empty">{{ bugs.length ? '当前筛选下没有Bug' : '当前没有报告Bug' }}</div><article v-for="bug in filteredBugs" :key="bug.id" class="issue-row"><div><el-button link type="primary" class="issue-title" @click="openTask(bug)">{{ bug.name }}</el-button><p class="muted">负责人 {{ bug.assigneeName }} · 截止 {{ bug.dueDate || '未设置' }}</p></div><div class="page-actions"><el-tag :type="TASK_STATUS_TYPES[bug.status]">{{ BUG_STATUS_LABELS[bug.status] }}</el-tag><el-button v-if="canChangeTask(bug)" :loading="busyTaskId === bug.id" :disabled="!!busyTaskId || busyNode" @click="quickStatus(bug)">{{ BUG_NEXT_ACTION[bug.status].label }}</el-button><el-button @click="openTask(bug)">查看详情</el-button></div></article></el-tab-pane>
          <el-tab-pane :label="'活动记录 ' + logs.length" name="activity"><div v-if="!logs.length" class="empty">暂无活动记录</div><article v-for="log in logs" :key="log.id" class="node-log"><strong>{{ logDetail(log) }}</strong><p class="muted">{{ log.userName || '系统' }} · {{ formatDateTime(log.createdAt) }}</p></article></el-tab-pane>
          <el-tab-pane v-if="legacyTasks.length" :label="'历史任务 ' + legacyTasks.length" name="history"><el-alert title="保留此前创建的任务、记录与附件。功能交付由负责人确认，不再按历史任务完成率计算。" type="info" :closable="false" /><div class="section-heading"><h2>历史任务</h2><el-button-group><el-button :type="mode === 'list' ? 'primary' : 'default'" plain @click="goView('list')">列表</el-button><el-button :type="mode === 'kanban' ? 'primary' : 'default'" plain @click="goView('kanban')">看板</el-button></el-button-group></div><el-select v-model="statusFilter" class="status-filter" aria-label="历史任务状态筛选" @change="updateFilters"><el-option label="全部状态" value="ALL"/><el-option label="未完成任务" value="OPEN"/><el-option v-for="status in TASK_STATUSES" :key="status" :label="TASK_STATUS_LABELS[status]" :value="status"/></el-select><template v-if="mode === 'list'"><article v-for="task in filteredTasks" :key="task.id" class="issue-row"><div><el-button link type="primary" class="issue-title" @click="openTask(task)">{{ task.name }}</el-button><p class="muted">{{ task.assigneeName }} · {{ task.dueDate || '未设截止日' }} · {{ task.noteCount || 0 }} 条记录</p></div><div class="page-actions"><el-tag :type="TASK_STATUS_TYPES[task.status]">{{ TASK_STATUS_LABELS[task.status] }}</el-tag><el-button v-if="canChangeTask(task)" :loading="busyTaskId === task.id" :disabled="!!busyTaskId" @click="quickStatus(task)">{{ TASK_NEXT_ACTION[task.status].label }}</el-button><el-button @click="openTask(task)">查看详情</el-button></div></article><div v-if="!filteredTasks.length" class="empty">当前筛选下没有历史任务</div></template><div v-else class="node-kanban"><section v-for="column in columns" :key="column.status" class="node-kanban-column"><h3>{{ column.label }} · {{ column.tasks.length }}</h3><article v-for="task in column.tasks" :key="task.id" class="node-kanban-card"><el-button link type="primary" class="issue-title" @click="openTask(task)">{{ task.name }}</el-button><p class="muted">{{ task.assigneeName }}</p><el-button v-if="canChangeTask(task)" :loading="busyTaskId === task.id" :disabled="!!busyTaskId" @click="quickStatus(task)">{{ TASK_NEXT_ACTION[task.status].label }}</el-button></article></section></div></el-tab-pane>
        </el-tabs>
      </section>
      <WorkItemFormDialog v-model="workItemDialog" :project-id="node.projectId" :work-item="node" :members="members" @saved="load(true)" />
      <BugReportDialog v-model="bugDialog" :project-id="node.projectId" :node-id="node.id" @saved="load(true)" />
      <el-dialog :model-value="riskDialog" :title="riskMode === 'block' ? '标记工作项受阻' : '更新延期说明'" width="min(560px, calc(100vw - 28px))" :close-on-click-modal="false" :before-close="closeRisk" @update:model-value="riskDialog = $event"><el-form label-position="top" :disabled="savingRisk"><el-form-item :label="riskMode === 'block' ? '受阻原因' : '延期说明'" required><el-input v-model="riskReason" type="textarea" :rows="5" /></el-form-item><p v-if="riskMode === 'overdue'" class="muted">更新说明不会自动调整计划日期。</p></el-form><template #footer><el-button :disabled="savingRisk" @click="closeRisk()">取消</el-button><el-button type="primary" :loading="savingRisk" :disabled="!canMutateNode || (riskMode === 'overdue' && !node.flags.overdue)" @click="saveRisk">{{ riskMode === 'block' ? '保存并标记受阻' : '保存说明' }}</el-button></template></el-dialog>
    </template>
    <div v-else-if="loading" class="surface empty">正在加载工作项…</div>
  </div>
</template>

<style scoped>
.node-page{min-height:240px}.node-page-head{align-items:flex-start}.node-title{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0 8px}.node-title h1{margin:0;overflow-wrap:anywhere}.node-page-head p{line-height:1.7}.node-facts{display:flex;gap:22px;flex-wrap:wrap;margin-bottom:20px;color:var(--muted)}.node-facts strong{color:var(--navy);margin-left:6px}.node-facts button{font:inherit;padding:0;border:0;background:none;color:var(--blue);cursor:pointer}.feature-description{padding:18px 0 26px}.feature-description>p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}.section-actions{padding-top:18px;border-top:1px solid var(--line)}.material-files{padding-top:28px;margin-top:28px;border-top:1px solid var(--line)}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:18px;margin:14px 0 20px}.section-heading h2,.section-heading p{margin:0 0 8px}.status-filter{width:160px;margin-bottom:15px}.issue-row{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 0;border-bottom:1px solid var(--line)}.issue-row p{font-size:13px;margin:7px 0 0}.issue-title{height:auto;font-size:15px;white-space:normal;line-height:1.7;text-align:left}.issue-title :deep(span){white-space:normal;overflow-wrap:anywhere}.node-risk{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 22px;border:1px solid var(--line);border-radius:10px;margin-bottom:18px}.node-risk-overdue{background:#fff5f5;border-color:#f6d6da}.node-risk-blocked{background:#f7f3fd;border-color:#e2d7f3}.node-risk strong{font-size:16px}.node-risk p{margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.65}.node-risk small{color:var(--muted)}.node-log{padding:18px 0;border-bottom:1px solid var(--line)}.node-log p{font-size:13px}.node-kanban{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.node-kanban-column{padding:16px;background:#f1f4f9;border-radius:10px;min-height:200px}.node-kanban-card{background:#fff;padding:16px;border:1px solid var(--line);border-radius:8px;margin-top:12px}
@media(max-width:900px){.node-page-head,.node-risk,.section-heading,.issue-row{align-items:flex-start;flex-wrap:wrap}.issue-row>div:first-child{width:100%}.node-kanban{grid-template-columns:1fr}.node-title h1{font-size:25px}}
</style>
