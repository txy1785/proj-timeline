<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { ChangeRequest, ChangeRequestStatus, StageTarget, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectContext } from '../composables/useProjectContext'
import { formatDateTime } from '../utils/format'

const route = useRoute()
const id = Number(route.params.id)
const router = useRouter()
const auth = useAuthStore()
const { project, members, loading, error, isPm, refresh } = useProjectContext(id)
const tab = ref('basic')
const available = ref<User[]>([])
const requests = ref<ChangeRequest[]>([])
const requestScope = ref('ALL')
const auxiliaryError = ref('')
const auxLoading = ref(false)
const busy = ref(false)
const targetDialog = ref(false)
const requestDrawer = ref(false)
const selectedRequestId = ref(0)
const projectForm = reactive({ name: '', code: '', description: '', planStart: '', planEnd: '' })
const baseline = ref('')
let initializedProject = 0
let generation = 0
const targetForm = reactive({ id: 0, name: '', description: '', targetDate: '', hasMetric: false, metricName: '', metricUnit: '', metricTarget: undefined as number | undefined, metricCurrent: undefined as number | undefined })
const active = computed(() => project.value?.status === 'ACTIVE')
const canWrite = computed(() => isPm.value && active.value && !loading.value)
const basicDirty = computed(() => initializedProject === id && JSON.stringify(projectForm) !== baseline.value)
const selectedRequest = computed(() => requests.value.find(row => row.id === selectedRequestId.value))
const visibleRequests = computed(() => requestScope.value === 'ALL' ? requests.value : requests.value.filter(row => row.status === requestScope.value))
const requestedId = computed(() => Number(route.query.requestId) || 0)
const missingRequest = computed(() => requestedId.value > 0 && !auxLoading.value && !requests.value.some(row => row.id === requestedId.value))
const STATUS_LABELS: Record<ChangeRequestStatus, string> = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已驳回', CANCELLED: '已撤回' }
const STATUS_TYPES: Record<ChangeRequestStatus, 'warning' | 'success' | 'danger' | 'info'> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'info' }
const GLOBAL_LABELS = { ADMIN: '管理员', USER: '普通账号', VIEWER: '只读观察者' }
const requestComparison = computed(() => {
  const item = selectedRequest.value
  const module = project.value?.modules.find(value => value.id === item?.moduleId)
  if (!item) return []
  return [
    { label: '模块名称', current: module?.name || '当前模块不可用', requested: item.name },
    { label: '模块描述', current: module?.description || '未填写', requested: item.description || '未填写' },
    { label: '负责人', current: module?.ownerName || '未记录', requested: item.ownerName || '未记录' }
  ]
})

watch(() => [route.query.tab, route.query.requestId], () => {
  const value = String(route.query.tab || '')
  tab.value = requestedId.value ? 'requests' : ['basic', 'members', 'targets', 'requests'].includes(value) ? value : 'basic'
  if (requestedId.value && requests.value.some(item => item.id === requestedId.value)) {
    selectedRequestId.value = requestedId.value; requestDrawer.value = true
  }
}, { immediate: true })

function changeTab(value: string | number) {
  const next = String(value)
  router.replace({ path: route.path, query: { ...route.query, tab: next, requestId: next === 'requests' ? route.query.requestId : undefined } })
}
watch(requestDrawer, open => {
  if (!open && requestedId.value) router.replace({ path: route.path, query: { ...route.query, requestId: undefined } })
})

async function loadAuxiliary() {
  const current = ++generation
  auxLoading.value = true
  auxiliaryError.value = ''
  available.value = []
  try {
    const [rows, people] = await Promise.all([
      api<ChangeRequest[]>('GET', `/projects/${id}/change-requests`),
      canWrite.value ? api<User[]>('GET', `/projects/${id}/users`, undefined, { scope: 'available' }) : Promise.resolve([])
    ])
    if (current !== generation) return
    requests.value = rows
    available.value = people
    if (requestedId.value && rows.some(item => item.id === requestedId.value)) {
      selectedRequestId.value = requestedId.value; requestDrawer.value = true
    }
  } catch (e) { if (current === generation) auxiliaryError.value = (e as Error).message }
  finally { if (current === generation) auxLoading.value = false }
}

watch(project, value => {
  if (!value) return
  if (initializedProject !== id || !basicDirty.value) {
    Object.assign(projectForm, { name: value.name, code: value.code, description: value.description || '', planStart: value.planStart, planEnd: value.planEnd || '' })
    initializedProject = id
    baseline.value = JSON.stringify(projectForm)
  }
  void loadAuxiliary()
}, { immediate: true })

onBeforeRouteLeave(async () => {
  if (busy.value) return false
  if (!basicDirty.value) return true
  try { await ElMessageBox.confirm('项目信息有未保存的修改，离开后将放弃本次修改。', '未保存的项目信息', { confirmButtonText: '放弃修改并离开', cancelButtonText: '继续编辑', type: 'warning' }); return true }
  catch { return false }
})

async function reload() { await refresh(); if (project.value) await loadAuxiliary() }
async function perform(action: () => Promise<unknown>, success: string) {
  if (busy.value) return false
  busy.value = true
  try { await action(); ElMessage.success(success); await reload(); return true }
  catch (e) {
    ElMessage.error((e as Error).message)
    if (e instanceof ApiError && [403, 409].includes(e.status || 0)) await reload()
    return false
  }
  finally { busy.value = false }
}
async function saveProject() {
  if (!canWrite.value || busy.value) return
  if (!projectForm.name.trim() || !projectForm.code.trim() || !projectForm.planStart) { ElMessage.warning('请填写项目名称、代号和开始日期'); return }
  if (projectForm.planEnd && projectForm.planEnd < projectForm.planStart) { ElMessage.warning('完成日期不能早于开始日期'); return }
  busy.value = true
  try {
    await api('PUT', `/projects/${id}`, { ...projectForm, name: projectForm.name.trim(), code: projectForm.code.trim() })
    baseline.value = JSON.stringify(projectForm)
    ElMessage.success('项目信息已保存')
    await reload()
  } catch (e) { ElMessage.error((e as Error).message) } finally { busy.value = false }
}
async function addMember(userId: number) { if (canWrite.value) await perform(() => api('POST', `/projects/${id}/members`, { userId }), '成员已加入项目') }
async function removeMember(user: User) {
  if (!canWrite.value) return
  try { await ElMessageBox.confirm(`确认移出 ${user.name}？仍承担模块、工作项或任务责任的成员不能移出。`, '移出项目成员', { type: 'warning' }) } catch { return }
  await perform(() => api('DELETE', `/projects/${id}/members/${user.id}`), '成员已移出')
}
async function transfer(user: User) {
  if (!canWrite.value || user.globalRole === 'VIEWER' || !user.active) return
  try { await ElMessageBox.confirm(`确认将项目经理转交给 ${user.name}？转交后将按新的项目职务显示管理操作。`, '转交项目经理', { type: 'warning' }) } catch { return }
  await perform(() => api('PUT', `/projects/${id}/pm`, { userId: user.id }), '项目经理已转交')
}
async function changeArchive() {
  if (!isPm.value || !project.value || busy.value) return
  const restore = !active.value
  try { await ElMessageBox.confirm(restore ? '恢复后，项目成员可按各自权限继续维护业务内容。' : '归档后保留项目历史，工作项、任务、文件、周报与审批均只读。', restore ? '恢复项目' : '归档项目', { type: restore ? 'info' : 'warning', confirmButtonText: restore ? '恢复项目' : '确认归档' }) } catch { return }
  await perform(() => api('POST', `/projects/${id}/${restore ? 'unarchive' : 'archive'}`), restore ? '项目已恢复' : '项目已归档')
}
function openTarget(item?: StageTarget) {
  Object.assign(targetForm, { id: item?.id || 0, name: item?.name || '', description: item?.description || '', targetDate: item?.targetDate || '',
    hasMetric: item?.metricTarget != null || !!item?.metricName, metricName: item?.metricName || '', metricUnit: item?.metricUnit || '', metricTarget: item?.metricTarget, metricCurrent: item?.metricCurrent })
  targetDialog.value = true
}
async function saveTarget() {
  if (!canWrite.value || busy.value) return
  if (!targetForm.name.trim() || !targetForm.targetDate) { ElMessage.warning('请填写目标名称和日期'); return }
  if (project.value && (targetForm.targetDate < project.value.planStart || (project.value.planEnd && targetForm.targetDate > project.value.planEnd))) { ElMessage.warning('目标日期应在项目计划范围内'); return }
  if (targetForm.hasMetric && (!targetForm.metricName.trim() || !targetForm.metricTarget || targetForm.metricTarget <= 0)) { ElMessage.warning('请填写指标名称和大于 0 的目标值'); return }
  const metric = targetForm.hasMetric ? { metricName: targetForm.metricName.trim(), metricUnit: targetForm.metricUnit.trim() || null, metricTarget: targetForm.metricTarget, metricCurrent: targetForm.metricCurrent ?? 0 }
    : { metricName: null, metricUnit: null, metricTarget: null, metricCurrent: null }
  const payload = { name: targetForm.name.trim(), description: targetForm.description, targetDate: targetForm.targetDate, ...metric }
  const saved = await perform(() => targetForm.id ? api('PUT', `/stage-targets/${targetForm.id}`, payload) : api('POST', `/projects/${id}/stage-targets`, payload), targetForm.id ? '阶段目标已更新' : '阶段目标已创建')
  if (saved) targetDialog.value = false
}
async function deleteTarget(item: StageTarget) {
  if (!canWrite.value) return
  try { await ElMessageBox.confirm(`确认删除阶段性目标「${item.name}」？`, '删除阶段目标', { type: 'warning' }) } catch { return }
  await perform(() => api('DELETE', `/stage-targets/${item.id}`), '阶段目标已删除')
}
function openRequest(row: ChangeRequest) {
  selectedRequestId.value = row.id
  requestDrawer.value = true
  router.replace({ path: route.path, query: { ...route.query, tab: 'requests', requestId: row.id } })
}
async function decide(approved: boolean) {
  const item = selectedRequest.value
  if (!item || !canWrite.value || item.status !== 'PENDING' || busy.value) return
  let remark = ''
  if (approved) {
    try { await ElMessageBox.confirm('通过后将立即更新该模块的名称、描述和负责人。请确认当前内容与申请内容的差异。', '通过并生效', { confirmButtonText: '通过并生效', cancelButtonText: '继续检查' }) } catch { return }
  } else {
    try { const result = await ElMessageBox.prompt('驳回原因会展示给申请人。', '驳回申请', { inputType: 'textarea', inputPlaceholder: '请填写驳回原因', inputValidator: value => !!value?.trim() || '驳回必须填写原因' }); remark = result.value.trim() } catch { return }
  }
  await perform(async () => {
    const updated = await api<ChangeRequest>('PUT', `/change-requests/${item.id}/decision`, { approved, remark: remark || undefined })
    requests.value = requests.value.map(row => row.id === updated.id ? updated : row)
  }, approved ? '已通过并更新模块' : '申请已驳回')
}
async function withdraw(row: ChangeRequest) {
  if (!active.value || auth.isViewer || row.requestedBy !== auth.user?.id || row.status !== 'PENDING') return
  try { await ElMessageBox.confirm('确认撤回这条待审批申请？', '撤回申请') } catch { return }
  await perform(() => api('POST', `/change-requests/${row.id}/cancel`), '申请已撤回')
}
</script>

<template>
  <div v-loading="loading">
    <header class="page-head"><div><h1>项目设置</h1><p>维护项目基本信息、成员和阶段目标；日常交付在工作项中推进。</p></div><el-button @click="router.push(`/projects/${id}/work-items`)">查看工作项与模块</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="reload">重新加载项目</el-button></el-alert>
    <template v-if="project">
      <el-alert v-if="!isPm" title="当前账号可查看设置；项目信息、成员和阶段目标由项目经理维护。你提交的模块申请可在“模块申请”中查看。" type="info" :closable="false" class="section-gap" />
      <el-alert v-else-if="!active" title="项目已归档，内容只读。需要继续维护时，可在基本信息中恢复项目。" type="info" :closable="false" class="section-gap" />
      <el-tabs v-model="tab" class="settings-tabs" @tab-change="changeTab">
        <el-tab-pane label="基本信息" name="basic">
          <section class="surface settings-surface"><h2>项目基本信息</h2><el-form label-position="top" :disabled="!canWrite || busy" @submit.prevent="saveProject"><div class="form-grid"><el-form-item label="项目名称" required><el-input v-model="projectForm.name" /></el-form-item><el-form-item label="项目代号" required><el-input v-model="projectForm.code" /></el-form-item></div><el-form-item label="项目说明"><el-input v-model="projectForm.description" type="textarea" :rows="3" placeholder="说明本项目要交付什么，项目概览会优先展示。" /></el-form-item><div class="form-grid"><el-form-item label="项目开始日期" required><el-date-picker v-model="projectForm.planStart" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item><el-form-item label="项目完成日期（可选）"><el-date-picker v-model="projectForm.planEnd" value-format="YYYY-MM-DD" clearable placeholder="完成日期可稍后确定" style="width:100%" /></el-form-item></div><div v-if="canWrite" class="page-actions"><el-button type="primary" :loading="busy" @click="saveProject">保存项目信息</el-button><small v-if="basicDirty" class="muted">有未保存的修改</small></div></el-form></section>
          <section class="surface state-section"><div><h2>项目状态 · {{ active ? '进行中' : '已归档' }}</h2><p>归档保留项目与文件历史，并将业务内容设为只读。</p></div><el-button v-if="isPm" :type="active ? 'danger' : 'primary'" plain :loading="busy" @click="changeArchive">{{ active ? '归档项目' : '恢复项目' }}</el-button></section>
        </el-tab-pane>
        <el-tab-pane label="成员" name="members">
          <section v-loading="auxLoading" class="surface"><div class="section-heading"><div><h2>项目成员</h2><p class="muted">项目经理恰有一位；仍承担工作责任的成员需要先完成转交。</p></div><el-dropdown v-if="canWrite && available.length" :disabled="busy" @command="addMember"><el-button type="primary">添加成员</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="person in available" :key="person.id" :command="person.id">{{ person.name }} · {{ GLOBAL_LABELS[person.globalRole] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown><span v-else-if="canWrite && !auxLoading" class="muted">暂无可添加账号</span></div><el-alert v-if="auxiliaryError" :title="auxiliaryError" type="error" :closable="false"><el-button link type="primary" @click="loadAuxiliary">重试</el-button></el-alert><el-table :data="members"><el-table-column prop="name" label="成员" min-width="140"><template #default="scope">{{ scope.row.name }} <el-tag v-if="!scope.row.active" size="small" type="info">已停用</el-tag></template></el-table-column><el-table-column label="账号类型" width="140"><template #default="scope">{{ GLOBAL_LABELS[scope.row.globalRole as User['globalRole']] }}</template></el-table-column><el-table-column label="项目职务" width="140"><template #default="scope">{{ scope.row.projectRole === 'PM' ? '项目经理' : '项目成员' }}</template></el-table-column><el-table-column v-if="canWrite" label="操作" min-width="190"><template #default="scope"><template v-if="scope.row.projectRole !== 'PM'"><el-button v-if="scope.row.active && scope.row.globalRole !== 'VIEWER'" link type="primary" :disabled="busy" @click="transfer(scope.row)">转交项目经理</el-button><el-button link type="danger" :disabled="busy" @click="removeMember(scope.row)">移出</el-button></template></template></el-table-column></el-table></section>
        </el-tab-pane>
        <el-tab-pane label="阶段目标" name="targets">
          <section class="surface"><div class="section-heading"><div><h2>项目阶段性目标</h2><p class="muted">项目在特定日期要达到的关键目标。模块只做功能分组，不承载阶段日期。</p></div><el-button v-if="canWrite" type="primary" @click="openTarget()">新建目标</el-button></div><el-table :data="project.stageTargets" empty-text="还没有阶段目标，可稍后按交付计划补充"><el-table-column prop="name" label="目标" min-width="170" /><el-table-column prop="targetDate" label="目标日期" width="125" /><el-table-column prop="description" label="说明" min-width="180" /><el-table-column label="量化指标（可选）" min-width="190"><template #default="scope"><template v-if="scope.row.metricName">{{ scope.row.metricName }}：{{ scope.row.metricCurrent ?? 0 }}/{{ scope.row.metricTarget }} {{ scope.row.metricUnit }}<small class="metric-line">达成 {{ scope.row.progress }}%</small></template><span v-else class="muted">日期型目标，不推断达成度</span></template></el-table-column><el-table-column v-if="canWrite" label="操作" width="125"><template #default="scope"><el-button link type="primary" :disabled="busy" @click="openTarget(scope.row)">编辑</el-button><el-button link type="danger" :disabled="busy" @click="deleteTarget(scope.row)">删除</el-button></template></el-table-column></el-table></section>
        </el-tab-pane>
        <el-tab-pane label="模块申请" name="requests">
          <section v-loading="auxLoading" class="surface"><div class="section-heading"><div><h2>{{ isPm ? '模块变更申请' : '我的模块变更申请' }}</h2><p class="muted">只处理模块名称、描述与负责人变更；通过后直接生效。</p></div><el-select v-model="requestScope" aria-label="申请状态" style="width:160px"><el-option label="全部申请" value="ALL" /><el-option v-for="(label, value) in STATUS_LABELS" :key="value" :label="label" :value="value" /></el-select></div><el-alert v-if="auxiliaryError" :title="auxiliaryError" type="error" :closable="false"><el-button link type="primary" @click="loadAuxiliary">重新加载申请</el-button></el-alert><el-alert v-else-if="missingRequest" title="这条申请已不可用或不在当前账号可查看范围内。" type="info" :closable="false" /><el-table :data="visibleRequests" empty-text="当前范围内没有模块申请" @row-click="openRequest"><el-table-column prop="moduleName" label="模块" min-width="160"><template #default="scope"><el-button link type="primary" @click.stop="openRequest(scope.row)">{{ scope.row.moduleName }}</el-button></template></el-table-column><el-table-column prop="requesterName" label="申请人" width="110" /><el-table-column label="状态" width="100"><template #default="scope"><el-tag size="small" :type="STATUS_TYPES[scope.row.status as ChangeRequestStatus]">{{ STATUS_LABELS[scope.row.status as ChangeRequestStatus] }}</el-tag></template></el-table-column><el-table-column prop="reason" label="申请说明" min-width="190" show-overflow-tooltip /><el-table-column label="提交时间" width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="scope"><el-button link type="primary" @click.stop="openRequest(scope.row)">{{ scope.row.status === 'PENDING' && canWrite ? '查看并审批' : '查看详情' }}</el-button></template></el-table-column></el-table></section>
        </el-tab-pane>
      </el-tabs>
      <el-dialog v-model="targetDialog" :title="targetForm.id ? '编辑阶段性目标' : '新建阶段性目标'" width="min(580px, calc(100vw - 28px))" :close-on-click-modal="false"><el-form label-position="top" :disabled="busy || !canWrite"><el-form-item label="目标名称" required><el-input v-model="targetForm.name" /></el-form-item><el-form-item label="目标说明"><el-input v-model="targetForm.description" type="textarea" :rows="2" /></el-form-item><el-form-item label="目标日期" required><el-date-picker v-model="targetForm.targetDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item><el-checkbox v-model="targetForm.hasMetric">补充量化指标（可选）</el-checkbox><p class="muted">不填指标时保留日期型目标，不计算评分或自动推断完成。</p><template v-if="targetForm.hasMetric"><div class="form-grid"><el-form-item label="指标名称" required><el-input v-model="targetForm.metricName" maxlength="100" /></el-form-item><el-form-item label="单位"><el-input v-model="targetForm.metricUnit" maxlength="20" /></el-form-item></div><div class="form-grid"><el-form-item label="目标值" required><el-input-number v-model="targetForm.metricTarget" :min="0" :max="9999999999.99" :precision="2" style="width:100%" /></el-form-item><el-form-item label="当前值"><el-input-number v-model="targetForm.metricCurrent" :min="0" :max="9999999999.99" :precision="2" style="width:100%" /></el-form-item></div></template></el-form><template #footer><el-button :disabled="busy" @click="targetDialog = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!canWrite" @click="saveTarget">保存目标</el-button></template></el-dialog>
      <el-drawer v-model="requestDrawer" title="模块变更申请" size="min(680px, 100vw)"><template v-if="selectedRequest"><div class="request-meta"><strong>{{ selectedRequest.moduleName }}</strong><el-tag :type="STATUS_TYPES[selectedRequest.status]">{{ STATUS_LABELS[selectedRequest.status] }}</el-tag><p>申请人 {{ selectedRequest.requesterName }} · {{ formatDateTime(selectedRequest.createdAt) }}</p></div><h3>当前模块与申请内容</h3><p class="muted">当前内容来自现有模块；申请通过前不会生效。</p><el-table :data="requestComparison"><el-table-column prop="label" label="字段" width="105" /><el-table-column prop="current" label="当前内容" min-width="160" /><el-table-column label="申请内容" min-width="160"><template #default="scope"><span :class="{ 'changed-value': scope.row.current !== scope.row.requested }">{{ scope.row.requested }}</span><small v-if="scope.row.current === scope.row.requested" class="muted">（未变）</small></template></el-table-column></el-table><h3>申请说明</h3><p class="request-text">{{ selectedRequest.reason || '未填写' }}</p><template v-if="selectedRequest.status !== 'PENDING'"><h3>处理结果</h3><p>{{ STATUS_LABELS[selectedRequest.status] }}<template v-if="selectedRequest.deciderName"> · {{ selectedRequest.deciderName }}</template> <template v-if="selectedRequest.decidedAt">· {{ formatDateTime(selectedRequest.decidedAt) }}</template></p><p v-if="selectedRequest.decisionRemark" class="request-text">{{ selectedRequest.decisionRemark }}</p></template><el-alert v-if="!active" title="项目已归档，申请与审批只读。" type="info" :closable="false" /><div v-else-if="selectedRequest.status === 'PENDING'" class="drawer-actions"><template v-if="canWrite"><el-button type="primary" :loading="busy" @click="decide(true)">通过并生效</el-button><el-button :disabled="busy" @click="decide(false)">驳回</el-button></template><el-button v-if="!auth.isViewer && selectedRequest.requestedBy === auth.user?.id" :disabled="busy" @click="withdraw(selectedRequest)">撤回申请</el-button></div></template><div v-else class="empty">申请已不可用，请重新加载。</div></el-drawer>
    </template>
  </div>
</template>

<style scoped>
.section-gap{margin-bottom:20px}.settings-surface{max-width:980px}.settings-surface h2{margin-top:0}.section-heading,.state-section{display:flex;justify-content:space-between;align-items:center;gap:20px}.section-heading{margin-bottom:20px}.section-heading h2,.state-section h2{margin:0}.section-heading p,.state-section p{line-height:1.7;margin:8px 0 0}.state-section{margin-top:20px}.state-section p{color:var(--muted)}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.metric-line{display:block;color:var(--muted);margin-top:5px}.request-meta{display:flex;align-items:center;gap:14px;flex-wrap:wrap}.request-meta strong{font-size:20px}.request-meta p{width:100%;color:var(--muted);margin:0 0 16px}.request-text{white-space:pre-wrap;line-height:1.8;padding:15px;background:var(--el-fill-color-light);border-radius:8px}.changed-value{color:var(--blue);font-weight:600}.drawer-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:28px}.settings-tabs :deep(.el-tabs__content){overflow:visible}.page-actions{align-items:center}
@media(max-width:600px){.form-grid{grid-template-columns:1fr;gap:0}.section-heading,.state-section{align-items:flex-start;flex-wrap:wrap}.section-heading>.el-select{width:100%!important}}
</style>
