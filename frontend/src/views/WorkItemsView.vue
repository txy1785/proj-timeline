<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter, type LocationQuery } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { NodeItem, NodeStatus } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectContext } from '../composables/useProjectContext'
import { canEditModule, isWorkItemManager } from '../utils/permissions'
import { NODE_STATUS_LABELS, NODE_STATUS_TYPES, WORK_ITEM_TYPE_LABELS, workItemCounts, hasWorkItemRisk } from '../utils/workItem'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'
import BugReportDialog from '../components/BugReportDialog.vue'

const route = useRoute()
const id = Number(route.params.id)
const router = useRouter()
const auth = useAuthStore()
const { project, members, loading, error, businessDate, isPm, canCreateWorkItem, refresh } = useProjectContext(id)
const RISKS = ['exception', 'overdue', 'blocked', 'near']
const PENDINGS = ['unscheduled', 'unassigned']
const BATCH_LIMIT = 50
const defaultFilters = () => ({ q: '', module: 'all', owner: 'all', status: 'ALL', risk: '', pending: '', mine: false })
const workItemDialog = ref(false)
const bugDialog = ref(false)
const selectedModuleId = ref<number | null>()
const filters = reactive(defaultFilters())
const collapsed = reactive<Record<string, boolean>>({})
const isOpen = (node: NodeItem) => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status)
const active = computed(() => project.value?.status === 'ACTIVE')
const pmId = computed(() => members.value.find(member => member.projectRole === 'PM')?.id || 0)
const allNodes = computed(() => [...(project.value?.modules.flatMap(module => module.nodes || []) || []), ...(project.value?.projectWorkItems || [])])
const hasManagement = computed(() => isPm.value || allNodes.value.some(node => isWorkItemManager(auth.user, members.value, node)) || project.value?.modules.some(module => canEditModule(auth.user, members.value, module)))
const canReportBug = computed(() => !auth.isViewer && active.value && allNodes.value.some(node => node.status !== 'CANCELLED'))
const compactQuery = window.matchMedia('(max-width: 1100px)')
const compact = ref(compactQuery.matches)
const filtersOpen = ref(false)
function onCompactChange(event: MediaQueryListEvent) { compact.value = event.matches }
const extraFilterCount = computed(() => [filters.module !== 'all', filters.owner !== 'all', filters.status !== 'ALL', !!filters.risk, !!filters.pending, filters.mine].filter(Boolean).length)
const counts = computed(() => ({ inProgress: allNodes.value.filter(node => node.status === 'IN_PROGRESS').length,
  overdue: allNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status) && node.flags?.overdue).length,
  blocked: allNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status) && node.flags?.blocked).length }))
const filteredNodes = computed(() => allNodes.value.filter(node => {
  if (filters.q && !`${node.name} ${node.description || ''}`.toLowerCase().includes(filters.q.trim().toLowerCase())) return false
  if (filters.module !== 'all' && (filters.module === 'project' ? !!node.moduleId : node.moduleId !== Number(filters.module))) return false
  if (filters.owner !== 'all' && node.ownerId !== Number(filters.owner)) return false
  if (filters.status === 'ALL' ? node.status === 'CANCELLED' : node.status !== filters.status) return false
  if (filters.mine && !isWorkItemManager(auth.user, members.value, node)) return false
  if (filters.risk && !['NOT_STARTED', 'IN_PROGRESS'].includes(node.status)) return false
  if (filters.risk === 'exception' && !node.flags?.overdue && !node.flags?.blocked) return false
  if (filters.risk === 'overdue' && !node.flags?.overdue) return false
  if (filters.risk === 'blocked' && !node.flags?.blocked) return false
  if (filters.risk === 'near' && !node.flags?.near) return false
  if (filters.pending && !isOpen(node)) return false
  if (filters.pending === 'unscheduled' && node.planEnd) return false
  if (filters.pending === 'unassigned' && node.ownerId) return false
  return true
}))
const pendingCounts = computed(() => ({ unscheduled: allNodes.value.filter(node => isOpen(node) && !node.planEnd).length,
  unassigned: allNodes.value.filter(node => isOpen(node) && !node.ownerId).length }))
const groups = computed(() => {
  const showEmptyModules = !filters.q.trim() && filters.owner === 'all' && filters.status === 'ALL' && !filters.risk && !filters.pending && !filters.mine
  const modules = (project.value?.modules || []).map(module => ({ id: module.id, key: `m${module.id}`, name: module.name, ownerId: module.ownerId,
    ownerName: module.ownerName, totalNodes: module.nodes?.length || 0, settled: settled(module.nodes || []), nodes: filteredNodes.value.filter(node => node.moduleId === module.id) }))
  const general = { id: 0, key: 'project', name: '项目级工作项', ownerId: pmId.value, ownerName: '跨模块的测试、维护与上线保障', totalNodes: project.value?.projectWorkItems.length || 0,
    settled: settled(project.value?.projectWorkItems || []), nodes: filteredNodes.value.filter(node => !node.moduleId) }
  return [...modules, general].filter(group => group.nodes.length || (group.id > 0 && showEmptyModules
    && (filters.module === 'all' || filters.module === String(group.id))))
})
type Group = (typeof groups.value)[number]
const filtering = computed(() => !!filters.q.trim() || filters.module !== 'all' || filters.owner !== 'all' || filters.status !== 'ALL' || !!filters.risk || !!filters.pending || filters.mine)
const isCollapsed = (group: Group) => collapsed[group.key] ?? (group.settled && !filtering.value)
const anyCollapsed = computed(() => groups.value.some(isCollapsed))
function toggleGroup(group: Group) { collapsed[group.key] = !isCollapsed(group) }
function expandAll() { groups.value.forEach(group => { collapsed[group.key] = false }) }
function settled(nodes: NodeItem[]) { return nodes.every(node => node.status === 'DONE' || node.status === 'CANCELLED') }

const storageKey = `workItems:${auth.user?.id || 0}:${id}`
let pendingScrollY = 0
function restoreState() {
  let saved: { filters?: Record<string, unknown>; collapsed?: Record<string, unknown>; scrollY?: unknown } | null = null
  try { saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null') } catch { return }
  const defaults: Record<string, unknown> = defaultFilters()
  Object.keys(defaults).forEach(key => {
    if (typeof saved?.filters?.[key] === typeof defaults[key]) Object.assign(filters, { [key]: saved?.filters?.[key] })
  })
  Object.entries(saved?.collapsed || {}).forEach(([key, value]) => { if (typeof value === 'boolean') collapsed[key] = value })
  pendingScrollY = typeof saved?.scrollY === 'number' ? saved.scrollY : 0
}
function saveState() {
  try { sessionStorage.setItem(storageKey, JSON.stringify({ filters, collapsed, scrollY: window.scrollY })) } catch { /* storage unavailable or full */ }
}
function restoreScroll() {
  if (!pendingScrollY || !project.value) return
  const scrollY = pendingScrollY
  pendingScrollY = 0
  void nextTick(() => window.scrollTo(0, scrollY))
}
function applyQuery(query: LocationQuery) {
  if (query.risk !== undefined) filters.risk = RISKS.includes(String(query.risk)) ? String(query.risk) : ''
  if (query.pending !== undefined) filters.pending = PENDINGS.includes(String(query.pending)) ? String(query.pending) : ''
  if (query.ownerId !== undefined) filters.owner = Number(query.ownerId) > 0 ? String(query.ownerId) : 'all'
  if (query.moduleId !== undefined) filters.module = query.moduleId ? String(query.moduleId) : 'all'
}
restoreState()
watch(() => route.query, applyQuery, { immediate: true })
watch([filters, collapsed], saveState, { deep: true })
watch(project, restoreScroll)
onMounted(restoreScroll)

function resetFilters() { Object.assign(filters, defaultFilters()); router.replace({ path: route.path }) }
function openNode(row: NodeItem) { saveState(); router.push(`/nodes/${row.id}`) }
function createWork(moduleId?: number | null) { selectedModuleId.value = moduleId; workItemDialog.value = true }
function created() { void refresh() }
function onHeadAction(action: string) {
  if (action === 'modules') router.push(`/projects/${id}/modules`)
  else if (action === 'bug') bugDialog.value = true
  else openBatch()
}

const allowedTargets = computed(() => {
  const modules = (project.value?.modules || []).filter(module => canEditModule(auth.user, members.value, module))
    .map(module => ({ value: String(module.id), label: module.name }))
  return isPm.value ? [...modules, { value: 'project', label: '项目级工作项' }] : modules
})
const canQuickCreate = computed(() => active.value && !auth.isViewer && canCreateWorkItem.value && allowedTargets.value.length > 0)
const quickTitle = ref('')
const quickTarget = ref('')
const quickSaving = ref(false)
const quickUnknown = ref('')
const UNKNOWN_HINT = '可能已创建，请刷新核对'
function contextTarget() {
  const targets = allowedTargets.value
  if (filters.module === 'all' && isPm.value) return 'project'
  return targets.find(target => target.value === filters.module)?.value || targets[0]?.value || ''
}
watch(allowedTargets, targets => { if (!targets.some(target => target.value === quickTarget.value)) quickTarget.value = contextTarget() }, { immediate: true })
watch(() => filters.module, () => { quickTarget.value = contextTarget() })
function postWorkItem(name: string, target: string) {
  return api<NodeItem>('POST', `/projects/${id}/nodes`, { name, moduleId: target === 'project' ? null : Number(target) })
}
function errorText(err: unknown) {
  const failure = err as { response?: { data?: { msg?: string } }; message?: string }
  return failure?.response?.data?.msg || failure?.message || '保存失败'
}
// 只有 4xx 能确认服务端未创建；超时、断网、5xx 与 HTTP 200 的业务错误码都可能已写入
function isDefiniteFailure(err: unknown) { return err instanceof ApiError && !!err.status && err.status >= 400 && err.status < 500 }
async function quickCreate() {
  const name = quickTitle.value.trim()
  if (quickUnknown.value) { ElMessage.warning(`请先刷新核对「${quickUnknown.value}」是否已创建`); return }
  if (!name || quickSaving.value || !canQuickCreate.value || !quickTarget.value) return
  quickSaving.value = true
  try {
    await postWorkItem(name, quickTarget.value)
    quickTitle.value = ''
    ElMessage.success('已新建工作项')
    void refresh()
  } catch (err) {
    if (isDefiniteFailure(err)) ElMessage.error(errorText(err))
    else { quickUnknown.value = name; ElMessage.warning(`「${name}」${UNKNOWN_HINT}`) }
  } finally { quickSaving.value = false }
}

type BatchState = 'pending' | 'saving' | 'success' | 'failed' | 'unknown'
type BatchMode = 'titles' | 'history'
type HistoryStatus = 'DONE' | 'IN_PROGRESS'
type BatchRow = { title: string; status?: HistoryStatus; actualStart?: string; actualEnd?: string; error?: string }
const BATCH_STATE_LABELS: Record<BatchState, string> = { pending: '待录入', saving: '录入中', success: '已录入', failed: '失败', unknown: '结果未知' }
const batchDialog = ref(false)
const batchText = ref('')
const batchTarget = ref('')
const batchMode = ref<BatchMode>('titles')
const batchOwnerId = ref<number>()
const batchSaving = ref(false)
const batchItems = ref<(BatchRow & { state: BatchState; payload: Readonly<Record<string, unknown>> })[]>([])
let batchRunTarget = ''
const batchTitles = computed(() => batchText.value.split('\n').map(line => line.trim()).filter(Boolean))
const selectableMembers = computed(() => members.value.filter(member => member.active && member.globalRole !== 'VIEWER'))
const batchOwnerValid = computed(() => selectableMembers.value.some(member => member.id === batchOwnerId.value))
const batchRows = computed<BatchRow[]>(() => batchMode.value === 'history'
  ? batchText.value.split('\n').filter(line => line.trim()).map(parseHistoryLine)
  : batchTitles.value.map(title => ({ title })))
const batchInvalid = computed(() => batchRows.value.filter(row => row.error).length)
const batchReady = computed(() => !!batchRows.value.length && batchRows.value.length <= BATCH_LIMIT && !batchInvalid.value
  && !!batchTarget.value && (batchMode.value === 'titles' || isPm.value))
const batchPreview = computed(() => batchItems.value.length ? batchItems.value : batchRows.value.map(row => ({ ...row, state: 'pending' as BatchState })))
const batchFailed = computed(() => batchItems.value.filter(item => item.state === 'failed').length)
const batchUnknown = computed(() => batchItems.value.filter(item => item.state === 'unknown').length)
function isCalendarDate(text: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false
  const [year, month, day] = text.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}
function dateError(label: string, value: string) {
  if (!isCalendarDate(value)) return `${label}不是有效日期（YYYY-MM-DD）`
  if (!businessDate.value) return '业务日期未加载，无法校验'
  return value > businessDate.value ? `${label}晚于业务日期 ${businessDate.value}` : ''
}
function historyRowError(cells: string[], status: HistoryStatus) {
  const [title = '', actualStart = '', actualEnd = ''] = cells
  if (cells.length > 3) return '列数超过 3 列'
  if (!title) return '缺少标题'
  const dateIssue = (actualStart && dateError('实际开始', actualStart)) || (actualEnd && dateError('实际完成', actualEnd))
  if (dateIssue) return dateIssue
  if (actualStart && actualEnd && actualStart > actualEnd) return '实际开始晚于实际完成'
  if (status === 'IN_PROGRESS' && !actualStart) return '进行中需填写实际开始'
  if (status === 'IN_PROGRESS' && !batchOwnerValid.value) return '进行中需选择有效负责人'
  return ''
}
function parseHistoryLine(line: string): BatchRow {
  const cells = line.split('\t').map(cell => cell.trim())
  const [title = '', actualStart = '', actualEnd = ''] = cells
  const status: HistoryStatus = actualEnd ? 'DONE' : 'IN_PROGRESS'
  return { title, status, actualStart, actualEnd, error: historyRowError(cells, status) }
}
function defaultBatchOwner() { return selectableMembers.value.some(member => member.id === pmId.value) ? pmId.value : undefined }
function openBatch() {
  batchText.value = ''
  batchItems.value = []
  batchTarget.value = contextTarget()
  batchMode.value = 'titles'
  batchOwnerId.value = defaultBatchOwner()
  batchDialog.value = true
}
async function runBatch(items: typeof batchItems.value) {
  batchSaving.value = true
  let succeeded = 0
  for (const item of items) {
    item.state = 'saving'
    try { await api<NodeItem>('POST', `/projects/${id}/nodes`, item.payload); item.state = 'success'; item.error = ''; succeeded++ } catch (err) {
      const definite = isDefiniteFailure(err)
      item.state = definite ? 'failed' : 'unknown'
      item.error = definite ? errorText(err) : UNKNOWN_HINT
    }
  }
  batchSaving.value = false
  if (succeeded) void refresh()
}
function batchPayload(row: BatchRow, moduleId: number | null) {
  const base = { name: row.title, moduleId }
  if (batchMode.value === 'titles') return Object.freeze(base)
  return Object.freeze({ ...base, historical: true, status: row.status, actualStart: row.actualStart || null,
    actualEnd: row.actualEnd || null, ownerId: batchOwnerValid.value ? batchOwnerId.value : null })
}
function startBatch() {
  if (batchSaving.value || batchItems.value.length || !batchReady.value) return
  batchRunTarget = batchTarget.value
  const moduleId = batchRunTarget === 'project' ? null : Number(batchRunTarget)
  batchItems.value = batchRows.value.map(row => ({ ...row, error: '', state: 'pending', payload: batchPayload(row, moduleId) }))
  void runBatch(batchItems.value)
}
function retryBatch() { if (!batchSaving.value && batchRunTarget) void runBatch(batchItems.value.filter(item => item.state === 'failed')) }
async function confirmBatchClose(message = '尚有未录入的标题，关闭后将丢失，确定关闭？', title = '关闭批量录入') {
  if (batchSaving.value) return false
  const dirty = (!batchItems.value.length && !!batchTitles.value.length) || batchFailed.value > 0 || batchUnknown.value > 0
  if (!dirty) return true
  try { await ElMessageBox.confirm(message, title, { type: 'warning' }); return true } catch { return false }
}
function beforeBatchClose(done: () => void) { void confirmBatchClose().then(ok => { if (ok) done() }) }
async function closeBatch() { if (await confirmBatchClose()) batchDialog.value = false }
async function newBatch() {
  if (!await confirmBatchClose('失败的条目尚未录入，开始新批次将丢弃，确定继续？', '开始新批次')) return
  batchText.value = ''
  batchItems.value = []
  batchRunTarget = ''
}
function onQuickKeydown(event: KeyboardEvent) { if (!event.isComposing) void quickCreate() }
function ackQuickUnknown() { quickUnknown.value = '' }
const hasRisk = (node: NodeItem) => (['overdue', 'blocked', 'near'] as const).some(kind => hasWorkItemRisk(node, kind))

type EditAction = 'owner' | 'date' | 'status' | 'move'
const EDIT_ACTION_LABELS: Record<EditAction, string> = { owner: '调整负责人', date: '调整日期', status: '变更状态', move: '移动位置' }
const EDIT_STATE_LABELS: Record<BatchState, string> = { pending: '待执行', saving: '执行中', success: '成功', failed: '失败', unknown: '结果未知' }
const NEXT_STATUSES: Record<string, string[]> = { NOT_STARTED: ['IN_PROGRESS', 'CANCELLED'], IN_PROGRESS: ['DONE', 'CANCELLED'] }
const canEditRow = (node: NodeItem) => active.value && !auth.isViewer && isOpen(node) && isWorkItemManager(auth.user, members.value, node)
const locationOf = (node: NodeItem) => node.moduleId ? String(node.moduleId) : 'project'
const selectedIds = ref<number[]>([])
const visibleEditableIds = computed(() => new Set(groups.value.filter(group => !isCollapsed(group))
  .flatMap(group => group.nodes).filter(canEditRow).map(node => node.id)))
const selectedRows = computed(() => allNodes.value.filter(node => selectedIds.value.includes(node.id) && visibleEditableIds.value.has(node.id)))
watch(visibleEditableIds, ids => {
  const kept = selectedIds.value.filter(rowId => ids.has(rowId))
  if (kept.length !== selectedIds.value.length) selectedIds.value = kept
})
function toggleRow(node: NodeItem, checked: boolean) {
  selectedIds.value = checked ? [...selectedIds.value, node.id] : selectedIds.value.filter(rowId => rowId !== node.id)
}
function statusOptionsFor(rows: NodeItem[]) {
  const first = rows[0] ? NEXT_STATUSES[rows[0].status] || [] : []
  return first.filter(status => rows.every(row => NEXT_STATUSES[row.status]?.includes(status)))
}
function moveTargetsFor(rows: NodeItem[]) { return allowedTargets.value.filter(target => rows.every(row => locationOf(row) !== target.value)) }

type DateMode = 'keep' | 'set' | 'clear'
type EditRequest = Readonly<{ method: string; url: string; body: Readonly<Record<string, unknown>> }>
const DATE_MODE_LABELS: Record<DateMode, string> = { keep: '保留', set: '设为', clear: '清空' }
const editDialog = ref(false)
const editAction = ref<EditAction>('owner')
const editSaving = ref(false)
const editStarted = ref(false)
const editItems = ref<{ node: NodeItem; state: BatchState; error?: string; request?: EditRequest }[]>([])
const blankEditForm = () => ({ ownerId: undefined as number | undefined, startMode: 'keep' as DateMode, planStart: '', endMode: 'keep' as DateMode, planEnd: '', status: '', moduleTarget: '', reason: '' })
const editForm = reactive(blankEditForm())
type EditForm = ReturnType<typeof blankEditForm>
let editRunForm: EditForm = { ...editForm }
const editRows = computed(() => editItems.value.map(item => item.node))
const editStatusOptions = computed(() => statusOptionsFor(editRows.value))
const editMoveTargets = computed(() => moveTargetsFor(editRows.value))
const editFailed = computed(() => editItems.value.filter(item => item.state === 'failed').length)
function nextDate(mode: DateMode, value: string, current?: string | null) { return mode === 'keep' ? current || '' : mode === 'set' ? value : '' }
function datesAfter(node: NodeItem, form: EditForm) {
  return { start: nextDate(form.startMode, form.planStart, node.planStart), end: nextDate(form.endMode, form.planEnd, node.planEnd) }
}
function dateRowError(node: NodeItem, form: EditForm) {
  const { start, end } = datesAfter(node, form)
  return start && end && start > end ? `调整后开始 ${start} 晚于完成 ${end}` : ''
}
const dateRange = (start?: string | null, end?: string | null) => `${start || '未设'} ~ ${end || '未设'}`
function dateAfterText(node: NodeItem) {
  const { start, end } = datesAfter(node, editStarted.value ? editRunForm : editForm)
  return dateRange(start, end)
}
const dateFormComplete = computed(() => (editForm.startMode !== 'keep' || editForm.endMode !== 'keep')
  && (editForm.startMode !== 'set' || !!editForm.planStart) && (editForm.endMode !== 'set' || !!editForm.planEnd))
const dateInvalidRows = computed(() => editRows.value.filter(node => dateRowError(node, editForm)).length)
const editReady = computed(() => {
  if (editAction.value === 'owner') return selectableMembers.value.some(member => member.id === editForm.ownerId)
  if (editAction.value === 'date') return dateFormComplete.value && !dateInvalidRows.value
  if (editAction.value === 'status') return editStatusOptions.value.includes(editForm.status)
  return editMoveTargets.value.some(target => target.value === editForm.moduleTarget) && !!editForm.reason.trim()
})
function dateModeText(label: string, mode: DateMode, value: string) { return `${label}${mode === 'set' ? '设为 ' + (value || '（未选日期）') : DATE_MODE_LABELS[mode]}` }
const editTargetLabel = computed(() => {
  if (editAction.value === 'owner') return members.value.find(member => member.id === editForm.ownerId)?.name || '未选择'
  if (editAction.value === 'date') {
    const text = `${dateModeText('开始', editForm.startMode, editForm.planStart)}；${dateModeText('完成', editForm.endMode, editForm.planEnd)}`
    return dateInvalidRows.value ? `${text}（${dateInvalidRows.value} 项日期顺序有误）` : text
  }
  if (editAction.value === 'status') return NODE_STATUS_LABELS[editForm.status as NodeStatus] || '未选择'
  const target = allowedTargets.value.find(item => item.value === editForm.moduleTarget)?.label
  return target ? `${target}${editForm.reason.trim() ? '；原因：' + editForm.reason.trim() : '（需填写原因）'}` : '未选择'
})
function openEdit(action: EditAction, rows: NodeItem[]) {
  editAction.value = action
  editItems.value = rows.map(node => ({ node, state: 'pending' }))
  editStarted.value = false
  Object.assign(editForm, blankEditForm())
  editDialog.value = true
}
function datePayload(form: EditForm) {
  const body: Record<string, string | null> = {}
  if (form.startMode !== 'keep') body.planStart = form.startMode === 'set' ? form.planStart : null
  if (form.endMode !== 'keep') body.planEnd = form.endMode === 'set' ? form.planEnd : null
  return body
}
function buildEditRequest(node: NodeItem, form: EditForm): EditRequest {
  const freeze = (method: string, url: string, body: Record<string, unknown>) => Object.freeze({ method, url, body: Object.freeze(body) })
  if (editAction.value === 'owner') return freeze('PUT', `/nodes/${node.id}`, { ownerId: form.ownerId })
  if (editAction.value === 'date') return freeze('PUT', `/nodes/${node.id}`, datePayload(form))
  if (editAction.value === 'status') return freeze('POST', `/nodes/${node.id}/status`, { status: form.status, expectedStatus: node.status })
  const moduleId = form.moduleTarget === 'project' ? null : Number(form.moduleTarget)
  return freeze('POST', `/nodes/${node.id}/move`, { moduleId, expectedModuleId: node.moduleId ?? null, reason: form.reason.trim() })
}
async function runEdit(items: typeof editItems.value) {
  editSaving.value = true
  editStarted.value = true
  const succeededIds: number[] = []
  for (const item of items) {
    if (!item.request) continue
    item.state = 'saving'
    try { await api<unknown>(item.request.method, item.request.url, item.request.body); item.state = 'success'; item.error = ''; succeededIds.push(item.node.id) } catch (err) { item.state = 'failed'; item.error = errorText(err) }
  }
  editSaving.value = false
  if (!succeededIds.length) return
  selectedIds.value = selectedIds.value.filter(rowId => !succeededIds.includes(rowId))
  void refresh()
}
function startEdit() {
  if (editSaving.value || editStarted.value || !editReady.value) return
  editRunForm = Object.freeze({ ...editForm })
  editItems.value.forEach(item => { item.request = buildEditRequest(item.node, editRunForm) })
  void runEdit(editItems.value)
}
function retryEdit() { if (!editSaving.value) void runEdit(editItems.value.filter(item => item.state === 'failed')) }
function beforeEditClose(done: () => void) { if (!editSaving.value) done() }
const anyRunning = () => quickSaving.value || batchSaving.value || editSaving.value
function guardLeave() {
  if (!anyRunning()) return true
  ElMessage.warning('操作执行中，请等待完成后再离开')
  return false
}
function onBeforeUnload(event: BeforeUnloadEvent) { if (anyRunning()) event.preventDefault() }
onBeforeRouteLeave(guardLeave)
onBeforeRouteUpdate(guardLeave)
window.addEventListener('beforeunload', onBeforeUnload)
compactQuery.addEventListener('change', onCompactChange)
onBeforeUnmount(() => { window.removeEventListener('beforeunload', onBeforeUnload); compactQuery.removeEventListener('change', onCompactChange) })
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="refresh">重新加载</el-button></el-alert>
    <template v-if="project">
      <header class="page-head work-head"><div><h1>工作项</h1><p>明确每项交付的负责人、日期与下一步，按功能模块分组。</p></div><div class="page-actions">
        <template v-if="!compact"><el-button @click="router.push(`/projects/${id}/modules`)">查看功能模块</el-button><el-button v-if="canReportBug" @click="bugDialog = true">报告 Bug</el-button><el-button v-if="canQuickCreate" @click="openBatch">批量录入</el-button></template>
        <el-dropdown v-else trigger="click" @command="onHeadAction"><el-button>更多</el-button><template #dropdown><el-dropdown-menu>
          <el-dropdown-item command="modules">查看功能模块</el-dropdown-item><el-dropdown-item v-if="canReportBug" command="bug">报告 Bug</el-dropdown-item><el-dropdown-item v-if="canQuickCreate" command="batch">批量录入</el-dropdown-item>
        </el-dropdown-menu></template></el-dropdown>
        <el-button v-if="canCreateWorkItem" type="primary" @click="createWork()">新建工作项</el-button>
      </div></header>
      <div class="work-summary"><span>未取消功能 {{ workItemCounts(allNodes).total }}</span><button @click="filters.status = 'IN_PROGRESS'">进行中 {{ counts.inProgress }}</button><button :class="{ danger: counts.overdue > 0 }" @click="filters.risk = 'overdue'">延期 {{ counts.overdue }}</button><button :class="{ purple: counts.blocked > 0 }" @click="filters.risk = 'blocked'">受阻 {{ counts.blocked }}</button><button @click="filters.pending = 'unscheduled'">未排期 {{ pendingCounts.unscheduled }}</button><button @click="filters.pending = 'unassigned'">未分配 {{ pendingCounts.unassigned }}</button></div>
      <section class="surface filters-panel">
        <div class="filters-search">
          <el-input v-model="filters.q" clearable placeholder="搜索工作项" aria-label="搜索工作项" />
          <el-button v-if="compact" class="filters-toggle" :aria-expanded="filtersOpen" @click="filtersOpen = !filtersOpen">筛选<template v-if="extraFilterCount"> · {{ extraFilterCount }}</template></el-button>
        </div>
        <template v-if="!compact || filtersOpen">
          <el-select v-model="filters.module" aria-label="所属位置"><el-option label="全部位置" value="all" /><el-option v-for="module in project.modules" :key="module.id" :label="module.name" :value="String(module.id)" /><el-option label="项目级工作项" value="project" /></el-select>
          <el-select v-model="filters.owner" aria-label="负责人"><el-option label="全部负责人" value="all" /><el-option v-for="member in members" :key="member.id" :label="member.name" :value="String(member.id)" /></el-select>
          <el-select v-model="filters.status" aria-label="工作项状态"><el-option label="全部状态（不含已取消）" value="ALL" /><el-option v-for="(label, status) in NODE_STATUS_LABELS" :key="status" :label="label" :value="status" /></el-select>
          <el-select v-model="filters.risk" clearable placeholder="全部风险" aria-label="风险"><el-option label="延期或受阻" value="exception" /><el-option label="已延期" value="overdue" /><el-option label="受阻" value="blocked" /><el-option label="临近到期" value="near" /></el-select>
          <el-select v-model="filters.pending" clearable placeholder="全部待处理" aria-label="待处理"><el-option label="未排期" value="unscheduled" /><el-option label="未分配" value="unassigned" /></el-select>
          <el-checkbox v-if="hasManagement" v-model="filters.mine">我的管理范围</el-checkbox>
        </template>
        <el-button v-if="filtering" link type="primary" @click="resetFilters">清除筛选</el-button>
      </section>
      <div v-if="canQuickCreate" class="quick-create">
        <el-select v-if="allowedTargets.length > 1" v-model="quickTarget" aria-label="新建位置"><el-option v-for="target in allowedTargets" :key="target.value" :label="target.label" :value="target.value" /></el-select>
        <el-input v-model="quickTitle" :disabled="quickSaving" placeholder="输入工作项标题，按 Enter 快速新建（不设日期）" aria-label="快速新建工作项" @keydown.enter.prevent="onQuickKeydown" />
      </div>
      <el-alert v-if="canQuickCreate && quickUnknown" class="quick-unknown" type="warning" :closable="false" :title="`「${quickUnknown}」${UNKNOWN_HINT}`">
        <el-button link type="primary" @click="refresh">刷新</el-button>
        <el-button link type="primary" @click="ackQuickUnknown">已核对，继续新建</el-button>
      </el-alert>
      <section v-if="!groups.length" class="surface empty"><h2>{{ filtering ? '当前筛选下没有工作项' : '项目还没有工作项' }}</h2><p>{{ filtering ? '可以清除筛选查看全部工作项。' : '先建立一个小功能，明确负责人、日期与交付资料。' }}</p><el-button v-if="filtering" @click="resetFilters">清除筛选</el-button><el-button v-else-if="canCreateWorkItem" type="primary" @click="createWork()">建立首个工作项</el-button><el-button v-if="!filtering && isPm && active" @click="router.push(`/projects/${id}/modules`)">查看并新建功能模块</el-button></section>
      <section v-else class="surface work-list">
        <div class="list-tools">
          <div v-if="selectedRows.length" class="bulk-bar">
            <span>已选 {{ selectedRows.length }} 项</span>
            <el-button size="small" @click="openEdit('owner', selectedRows)">负责人</el-button>
            <el-button size="small" @click="openEdit('date', selectedRows)">日期</el-button>
            <el-button size="small" :disabled="!statusOptionsFor(selectedRows).length" @click="openEdit('status', selectedRows)">状态</el-button>
            <el-button size="small" :disabled="!moveTargetsFor(selectedRows).length" @click="openEdit('move', selectedRows)">移动</el-button>
            <el-button link type="primary" @click="selectedIds = []">取消选择</el-button>
          </div>
          <el-button v-if="anyCollapsed" link type="primary" @click="expandAll">展开全部</el-button>
        </div>
        <div class="table-scroll">
          <table class="work-table">
            <thead><tr><th class="col-check"></th><th class="col-name">工作项</th><th class="col-owner">负责人</th><th class="col-date">计划完成</th><th class="col-status">状态</th><th class="col-deliver">交付资料</th><th class="col-risk">风险</th><th class="col-actions">操作</th></tr></thead>
            <tbody v-for="group in groups" :key="group.key">
              <tr class="group-row">
                <td colspan="8">
                  <div class="group-cell">
                    <button class="group-toggle" :aria-expanded="!isCollapsed(group)" @click="toggleGroup(group)"><span class="caret" :class="{ open: !isCollapsed(group) }">▸</span>{{ group.id ? '' : group.name }}</button>
                    <RouterLink v-if="group.id" :to="`/projects/${id}/modules/${group.id}`" @click="saveState">{{ group.name }}</RouterLink>
                    <small>{{ group.nodes.length }}</small>
                    <span class="muted group-owner">{{ group.id ? '模块负责人 · ' + group.ownerName : group.ownerName }}</span>
                    <el-button v-if="active && !auth.isViewer && (isPm || (group.id > 0 && group.ownerId === auth.user?.id))" link type="primary" class="group-add" @click="createWork(group.id || null)">新建工作项</el-button>
                  </div>
                </td>
              </tr>
              <template v-if="!isCollapsed(group)">
                <tr v-if="!group.nodes.length" class="empty-row"><td colspan="8" class="muted">{{ group.totalNodes ? '暂无未取消的工作项' : '本模块尚未拆分工作项' }}</td></tr>
                <tr v-for="node in group.nodes" :key="node.id" class="node-row" @click="openNode(node)">
                  <td class="col-check" @click.stop><el-checkbox v-if="canEditRow(node)" :model-value="selectedIds.includes(node.id)" :aria-label="`选择 ${node.name}`" @change="toggleRow(node, !!$event)" /></td>
                  <td class="col-name"><div class="name-cell"><el-button link type="primary" @click.stop="openNode(node)">{{ node.name }}</el-button><small class="row-type">{{ WORK_ITEM_TYPE_LABELS[node.type] }}</small></div></td>
                  <td :class="{ muted: !node.ownerName }">{{ node.ownerName || '未分配' }}</td>
                  <td><span :class="{ muted: !node.planEnd }">{{ node.planEnd || '待排期' }}</span></td>
                  <td><el-tag size="small" :type="NODE_STATUS_TYPES[node.status as NodeStatus]">{{ NODE_STATUS_LABELS[node.status as NodeStatus] }}</el-tag></td>
                  <td><span v-if="node.deliverableTotal">{{ node.deliverableProvided || 0 }}/{{ node.deliverableTotal }} 已提供</span><span v-else class="muted">未设交付要求</span></td>
                  <td><div class="flags"><el-tag v-if="hasWorkItemRisk(node, 'overdue')" size="small" type="danger">已延期</el-tag><el-tag v-if="hasWorkItemRisk(node, 'blocked')" size="small" type="warning">受阻</el-tag><el-tag v-if="hasWorkItemRisk(node, 'near')" size="small" type="warning">临近</el-tag><span v-if="!hasRisk(node)" class="muted">—</span></div></td>
                  <td class="col-actions" @click.stop>
                    <el-dropdown v-if="canEditRow(node)" trigger="click" @command="openEdit($event as EditAction, [node])">
                      <el-button link type="primary">编辑</el-button>
                      <template #dropdown><el-dropdown-menu>
                        <el-dropdown-item command="owner">{{ EDIT_ACTION_LABELS.owner }}</el-dropdown-item>
                        <el-dropdown-item command="date">{{ EDIT_ACTION_LABELS.date }}</el-dropdown-item>
                        <el-dropdown-item command="status" :disabled="!statusOptionsFor([node]).length">{{ EDIT_ACTION_LABELS.status }}</el-dropdown-item>
                        <el-dropdown-item command="move" :disabled="!moveTargetsFor([node]).length">{{ EDIT_ACTION_LABELS.move }}</el-dropdown-item>
                      </el-dropdown-menu></template>
                    </el-dropdown>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </section>
      <el-dialog v-model="batchDialog" title="批量录入工作项" width="min(560px, calc(100vw - 24px))" :before-close="beforeBatchClose" :close-on-click-modal="!batchSaving" :close-on-press-escape="!batchSaving" :show-close="!batchSaving">
        <el-radio-group v-if="isPm" v-model="batchMode" :disabled="batchSaving || batchItems.length > 0" class="batch-target" aria-label="录入模式"><el-radio-button value="titles">普通标题</el-radio-button><el-radio-button value="history">历史成果</el-radio-button></el-radio-group>
        <el-select v-if="allowedTargets.length > 1" v-model="batchTarget" :disabled="batchSaving || batchItems.length > 0" aria-label="录入位置" class="batch-target"><el-option v-for="target in allowedTargets" :key="target.value" :label="target.label" :value="target.value" /></el-select>
        <el-select v-if="batchMode === 'history'" v-model="batchOwnerId" :disabled="batchSaving || batchItems.length > 0" clearable placeholder="选择负责人（进行中必填）" aria-label="历史成果负责人" class="batch-target"><el-option v-for="member in selectableMembers" :key="member.id" :label="member.name" :value="member.id" /></el-select>
        <el-input v-model="batchText" type="textarea" :rows="6" :disabled="batchItems.length > 0" :placeholder="batchMode === 'history' ? `每行：标题 TAB 实际开始 TAB 实际完成（YYYY-MM-DD），最多 ${BATCH_LIMIT} 行；有完成日期为已完成，否则为进行中` : `每行一个工作项标题，最多 ${BATCH_LIMIT} 行；录入后不设日期`" aria-label="批量标题" />
        <p class="batch-hint" :class="{ danger: batchRows.length > BATCH_LIMIT || batchInvalid }">共 {{ batchRows.length }} 条<template v-if="batchRows.length > BATCH_LIMIT">，超过 {{ BATCH_LIMIT }} 条上限</template><template v-if="!batchItems.length && batchInvalid">，{{ batchInvalid }} 条有误需修正</template></p>
        <ol v-if="batchPreview.length" class="batch-preview">
          <li v-for="(item, index) in batchPreview" :key="index"><span class="batch-title">{{ item.title || '（无标题）' }}</span><template v-if="item.status"><el-tag size="small" :type="NODE_STATUS_TYPES[item.status]">{{ NODE_STATUS_LABELS[item.status] }}</el-tag><small class="muted">{{ item.actualStart || '—' }} ~ {{ item.actualEnd || '—' }}</small></template><el-tag size="small" :type="item.state === 'success' ? 'success' : item.state === 'failed' ? 'danger' : item.state === 'unknown' ? 'warning' : 'info'">{{ BATCH_STATE_LABELS[item.state] }}</el-tag><small v-if="item.error" class="danger">{{ item.error }}</small></li>
        </ol>
        <el-alert v-if="batchUnknown" type="warning" :closable="false" :title="`${batchUnknown} 条${UNKNOWN_HINT}，这些条目不会自动重试`">
          <el-button link type="primary" :disabled="batchSaving" @click="refresh">刷新</el-button>
        </el-alert>
        <template #footer>
          <el-button :disabled="batchSaving" @click="closeBatch">关闭</el-button>
          <el-button v-if="batchItems.length" :disabled="batchSaving" @click="newBatch">新批次</el-button>
          <el-button v-if="batchFailed" type="warning" :loading="batchSaving" @click="retryBatch">重试失败（{{ batchFailed }}）</el-button>
          <el-button v-if="!batchItems.length" type="primary" :loading="batchSaving" :disabled="!batchReady" @click="startBatch">开始录入</el-button>
        </template>
      </el-dialog>
      <el-dialog v-model="editDialog" :title="`${EDIT_ACTION_LABELS[editAction]}（${editItems.length} 项）`" width="min(560px, calc(100vw - 24px))" :before-close="beforeEditClose" :close-on-click-modal="!editSaving" :close-on-press-escape="!editSaving" :show-close="!editSaving">
        <div class="edit-form">
          <el-select v-if="editAction === 'owner'" v-model="editForm.ownerId" :disabled="editStarted" placeholder="选择负责人" aria-label="目标负责人"><el-option v-for="member in selectableMembers" :key="member.id" :label="member.name" :value="member.id" /></el-select>
          <template v-else-if="editAction === 'date'">
            <div class="date-field">
              <el-radio-group v-model="editForm.startMode" :disabled="editStarted" aria-label="计划开始调整方式"><el-radio-button v-for="(label, mode) in DATE_MODE_LABELS" :key="mode" :value="mode">{{ label }}开始</el-radio-button></el-radio-group>
              <el-date-picker v-if="editForm.startMode === 'set'" v-model="editForm.planStart" type="date" value-format="YYYY-MM-DD" :disabled="editStarted" placeholder="计划开始" aria-label="计划开始" />
            </div>
            <div class="date-field">
              <el-radio-group v-model="editForm.endMode" :disabled="editStarted" aria-label="计划完成调整方式"><el-radio-button v-for="(label, mode) in DATE_MODE_LABELS" :key="mode" :value="mode">{{ label }}完成</el-radio-button></el-radio-group>
              <el-date-picker v-if="editForm.endMode === 'set'" v-model="editForm.planEnd" type="date" value-format="YYYY-MM-DD" :disabled="editStarted" placeholder="计划完成" aria-label="计划完成" />
            </div>
          </template>
          <el-select v-else-if="editAction === 'status'" v-model="editForm.status" :disabled="editStarted" placeholder="选择目标状态" aria-label="目标状态"><el-option v-for="status in editStatusOptions" :key="status" :label="NODE_STATUS_LABELS[status as NodeStatus]" :value="status" /></el-select>
          <template v-else>
            <el-select v-model="editForm.moduleTarget" :disabled="editStarted" placeholder="选择目标位置" aria-label="目标位置"><el-option v-for="target in editMoveTargets" :key="target.value" :label="target.label" :value="target.value" /></el-select>
            <el-input v-model="editForm.reason" type="textarea" :rows="2" :disabled="editStarted" placeholder="移动原因（必填）" aria-label="移动原因" />
          </template>
        </div>
        <p class="batch-hint">目标：{{ editTargetLabel }}</p>
        <ol class="batch-preview">
          <li v-for="item in editItems" :key="item.node.id"><span class="batch-title">{{ item.node.name }}</span><small v-if="editAction === 'date'" class="date-diff" :class="{ danger: !editStarted && dateRowError(item.node, editForm) }">{{ dateRange(item.node.planStart, item.node.planEnd) }} → {{ dateAfterText(item.node) }}</small><el-tag size="small" :type="item.state === 'success' ? 'success' : item.state === 'failed' ? 'danger' : 'info'">{{ EDIT_STATE_LABELS[item.state] }}</el-tag><small v-if="item.error" class="danger">{{ item.error }}</small><small v-else-if="!editStarted && editAction === 'date' && dateRowError(item.node, editForm)" class="danger">{{ dateRowError(item.node, editForm) }}</small></li>
        </ol>
        <template #footer>
          <el-button :disabled="editSaving" @click="editDialog = false">关闭</el-button>
          <el-button v-if="editFailed" type="warning" :loading="editSaving" @click="retryEdit">重试失败（{{ editFailed }}）</el-button>
          <el-button v-if="!editStarted" type="primary" :loading="editSaving" :disabled="!editReady" @click="startEdit">确认执行</el-button>
        </template>
      </el-dialog>
      <WorkItemFormDialog v-model="workItemDialog" :project-id="id" :module-id="selectedModuleId" :modules="project.modules" :can-create-project-level="isPm" :members="members" :default-plan-start="project.planStart" :business-date="businessDate" @saved="created" />
      <BugReportDialog v-model="bugDialog" :project-id="id" @saved="refresh" />
    </template>
  </div>
</template>

<style scoped>
.work-head{margin-bottom:16px}.work-summary{display:flex;gap:8px 20px;align-items:center;flex-wrap:wrap;margin-bottom:12px}.work-summary button{padding:0;background:none;border:0;font:inherit;color:inherit;cursor:pointer}.filters-panel{display:flex;gap:10px 12px;align-items:center;flex-wrap:wrap;margin-bottom:12px;padding:12px 16px}.filters-search{display:flex;gap:8px}.filters-search>.el-input{width:200px}.filters-panel>.el-select{width:140px}.flags{display:flex;flex-wrap:wrap;gap:5px}.empty p{color:var(--muted);line-height:1.7}
.quick-create{display:flex;gap:10px;margin-bottom:12px}.quick-create>.el-select{width:180px;flex:none}.quick-create>.el-input{flex:1;min-width:0}
.work-list{padding-top:8px}.list-tools{display:flex;justify-content:flex-end;min-height:24px}.table-scroll{overflow-x:auto;max-width:100%}
.work-table{width:100%;min-width:820px;border-collapse:collapse;font-size:14px}.work-table th{height:40px;padding:0 12px;text-align:left;font-weight:600;color:var(--muted);border-bottom:1px solid var(--el-border-color-lighter)}
.work-table td{padding:6px 12px;border-bottom:1px solid var(--el-border-color-lighter);vertical-align:middle}.col-name{min-width:220px}.col-owner{width:105px}.col-date{width:115px}.col-status{width:100px}.col-deliver{width:125px}.col-risk{width:130px}
.group-row td{height:40px;padding:0 12px;background:var(--el-fill-color-light)}.group-cell{display:flex;align-items:center;gap:10px;min-width:0}.group-cell small{color:var(--muted)}.group-owner{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.group-add{margin-left:auto}
.group-toggle{display:inline-flex;align-items:center;gap:6px;padding:0;background:none;border:0;font:inherit;font-weight:600;color:inherit;cursor:pointer}.caret{display:inline-block;transition:transform .15s}.caret.open{transform:rotate(90deg)}
.node-row{cursor:pointer}.node-row td{height:48px}.node-row:hover td{background:var(--el-fill-color-lighter)}.name-cell{display:flex;align-items:baseline;gap:8px;min-width:0}.name-cell .el-button{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}.row-type{color:var(--muted);flex:none}.empty-row td{height:44px}
.batch-target{width:100%;margin-bottom:10px}.batch-hint{margin:8px 0;color:var(--muted)}.batch-hint.danger,.batch-preview .danger{color:var(--el-color-danger)}.batch-preview{max-height:260px;overflow:auto;margin:0;padding-left:22px}.batch-preview li{display:flex;align-items:center;gap:8px;padding:4px 0}.batch-title{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.col-check{width:36px}.col-actions{width:70px}.bulk-bar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-right:auto}.bulk-bar .el-button+.el-button{margin-left:0}
.edit-form{display:flex;flex-direction:column;gap:10px}.edit-form>.el-select,.edit-form .el-date-editor{width:100%}
.date-field{display:flex;flex-direction:column;gap:6px}.date-diff{flex:none;color:var(--muted);white-space:nowrap}.date-diff.danger{color:var(--el-color-danger)}.quick-unknown{margin-bottom:12px}
@media(max-width:1100px){.filters-search{flex:1 1 100%}.filters-search>.el-input{flex:1;width:auto;min-width:0}.filters-toggle{flex:none}.filters-panel>.el-select{width:165px}}
@media(max-width:600px){.work-head{flex-wrap:nowrap;gap:8px}.work-head>div:first-child{flex:1 1 auto}.work-head h1{margin:0}.work-head p{display:none}.work-head .page-actions{width:auto;flex-wrap:nowrap;gap:8px}.work-head .page-actions .el-button+.el-button{margin-left:0}
  .work-summary{gap:6px 14px;font-size:13px;margin-bottom:10px}.work-summary button{min-height:32px}.filters-panel{padding:10px;margin-bottom:10px}.filters-panel>.el-select{width:100%}
  .filters-panel :deep(.el-input),.quick-create :deep(.el-input){--el-input-height:44px}.filters-panel :deep(.el-select__wrapper),.quick-create :deep(.el-select__wrapper){min-height:44px}.filters-panel .el-checkbox{height:44px}.quick-create{margin-bottom:10px}.quick-create>.el-select{width:116px}.group-row td{height:44px}.group-toggle{min-height:44px}.group-owner{display:none}}
</style>
