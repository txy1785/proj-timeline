<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { Report } from '../api/types'
import { useProjectContext } from '../composables/useProjectContext'
import { dateValue, weekStart } from '../utils/date'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const { project, loading: contextLoading, isPm } = useProjectContext(id)
const report = ref<Report>()
const history = ref<Report[]>([])
const selectedWeek = ref('')
const note = ref('')
const savedNote = ref('')
const recoveredDraft = ref('')
const loading = ref(true)
const busy = ref(false)
const loadError = ref('')
const mutationError = ref('')
let generation = 0
let loadedKey = ''
const dirty = computed(() => note.value !== savedNote.value)
const content = computed(() => report.value?.content || {})
const canWrite = computed(() => isPm.value && project.value?.status === 'ACTIVE' && report.value?.status === 'DRAFT' && !loading.value && !contextLoading.value)
const historyValue = computed(() => report.value?.status === 'PUBLISHED' ? report.value.weekStart : '')

async function mayDiscard() {
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('风险与说明尚未保存，切换后将放弃本次修改。', '保留当前说明？', { confirmButtonText: '放弃修改并继续', cancelButtonText: '继续编辑', type: 'warning' }); return true }
  catch { return false }
}

async function load(week?: string) {
  const current = ++generation
  if (week && !Number.isFinite(dateValue(week))) { loadError.value = '周日期格式不正确，请重新选择。'; loading.value = false; return }
  loading.value = true
  loadError.value = ''
  mutationError.value = ''
  try {
    const [value, previous] = await Promise.all([
      api<Report>('GET', `/projects/${id}/reports`, undefined, week ? { week } : undefined),
      api<Report[]>('GET', `/projects/${id}/reports/history`)
    ])
    if (current !== generation) return
    report.value = value
    selectedWeek.value = value.weekStart
    note.value = value.note || ''
    savedNote.value = note.value
    recoveredDraft.value = ''
    history.value = previous
    loadedKey = week || 'CURRENT'
  } catch (e) { if (current === generation) loadError.value = (e as Error).message }
  finally { if (current === generation) loading.value = false }
}

watch(() => route.query.week, async value => {
  const week = typeof value === 'string' ? value : undefined
  if ((week || 'CURRENT') === loadedKey && report.value) return
  if (week && report.value?.weekStart === weekStart(week)) {
    loadedKey = week
    selectedWeek.value = report.value.weekStart
    return
  }
  await load(week)
}, { immediate: true })

onBeforeRouteLeave(() => !busy.value && mayDiscard())
onBeforeRouteUpdate(async (to, from) => {
  if (to.query.week === from.query.week) return true
  const allowed = !busy.value && await mayDiscard()
  if (!allowed && report.value) selectedWeek.value = report.value.weekStart
  return allowed
})

function chooseWeek(value?: string | null) {
  const week = value ? weekStart(value) : undefined
  router.push({ path: route.path, query: { ...route.query, week } })
}
async function retry() { if (await mayDiscard()) await load(typeof route.query.week === 'string' ? route.query.week : undefined) }
function openNode(row: Record<string, unknown>) {
  const nodeId = Number(row.id)
  if (Number.isSafeInteger(nodeId) && nodeId > 0) router.push(`/nodes/${nodeId}`)
}
async function ensureDraft() {
  if (!report.value?.weekStart) throw new Error('周报尚未加载完成')
  if (report.value.id) return report.value
  const value = await api<Report>('POST', `/projects/${id}/reports`, { weekStart: report.value.weekStart })
  report.value = value
  return value
}
async function recoverConflict(error: unknown) {
  mutationError.value = (error as Error).message
  if (error instanceof ApiError && error.status === 409 && report.value) {
    const draft = note.value
    try {
      const latest = await api<Report>('GET', `/projects/${id}/reports`, undefined, { week: report.value.weekStart })
      if (latest.status === 'PUBLISHED') {
        report.value = latest
        savedNote.value = latest.note || ''
        note.value = savedNote.value
        if (draft !== savedNote.value) recoveredDraft.value = draft
      }
    } catch { /* Keep the original draft and error if a refresh also fails. */ }
  }
  ElMessage.error(mutationError.value)
}
async function save() {
  if (busy.value || !canWrite.value) return
  busy.value = true
  mutationError.value = ''
  try {
    const item = await ensureDraft()
    const value = await api<Report>('PUT', `/reports/${item.id}/note`, { note: note.value })
    report.value = value
    savedNote.value = note.value
    ElMessage.success('说明已保存，周报仍为草稿')
  } catch (e) { await recoverConflict(e) } finally { busy.value = false }
}
async function publish() {
  if (busy.value || !canWrite.value) return
  busy.value = true
  try { await ElMessageBox.confirm('发布后保留本次数据快照，并通知项目成员。当前填写的说明会先保存再发布。', '发布周报', { confirmButtonText: '保存并发布', cancelButtonText: '继续检查', type: 'info' }) }
  catch { busy.value = false; return }
  mutationError.value = ''
  let noteSaved = false
  try {
    const item = await ensureDraft()
    await api('PUT', `/reports/${item.id}/note`, { note: note.value })
    savedNote.value = note.value
    noteSaved = true
    report.value = await api<Report>('POST', `/reports/${item.id}/publish`)
    note.value = report.value.note || ''
    savedNote.value = note.value
    ElMessage.success('周报已发布，已保留本次快照')
    try { history.value = await api<Report[]>('GET', `/projects/${id}/reports/history`) }
    catch { ElMessage.warning('周报已发布，历史列表暂未刷新，可稍后重新加载。') }
  } catch (e) {
    await recoverConflict(e)
    if (noteSaved && report.value?.status !== 'PUBLISHED') mutationError.value = `说明已保存，周报尚未发布：${(e as Error).message}`
  } finally { busy.value = false }
}
</script>

<template>
  <div v-loading="loading || contextLoading">
    <header class="page-head"><div><h1>项目周报</h1><p v-if="report">{{ report.weekStart }} 至 {{ report.weekEnd }} · {{ report.status === 'PUBLISHED' ? '已发布快照' : '实时草稿' }}</p></div><div class="page-actions"><el-date-picker v-model="selectedWeek" value-format="YYYY-MM-DD" placeholder="选择一周中的日期" aria-label="周报所在周" :disabled="busy" @change="chooseWeek" /><el-select :model-value="historyValue" clearable placeholder="历史周报" aria-label="历史周报" :disabled="busy" @change="chooseWeek"><el-option v-for="item in history" :key="item.id" :value="item.weekStart" :label="`${item.weekStart} · 已发布`" /></el-select></div></header>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="section-gap"><el-button link type="primary" @click="retry">重新加载周报</el-button></el-alert>
    <template v-if="report && !loadError">
      <el-alert v-if="project?.status === 'ARCHIVED'" title="项目已归档，周报只读。" type="info" :closable="false" class="section-gap" />
      <el-alert v-else-if="report.status === 'DRAFT'" title="草稿随当前项目数据实时变化；发布后保留本次快照。" type="info" :closable="false" class="section-gap" />
      <div class="report-grid">
        <section class="surface report-section"><h2>本周交付 <span>{{ content.completedNodes?.length || 0 }}</span></h2><div v-if="!content.completedNodes?.length" class="empty">本周暂无已交付功能。</div><el-table v-else :data="content.completedNodes" @row-click="openNode"><el-table-column prop="name" label="工作项" min-width="180"><template #default="scope"><el-button link type="primary" @click.stop="openNode(scope.row)">{{ scope.row.name }}</el-button></template></el-table-column><el-table-column prop="actualEnd" label="完成日期" width="130" /></el-table></section>
        <section class="surface report-section"><h2>延期与受阻 <span>{{ content.exceptionNodes?.length || 0 }}</span></h2><div v-if="!content.exceptionNodes?.length" class="empty">当前没有延期或受阻记录。</div><article v-for="row in content.exceptionNodes" :key="String(row.id)" class="report-node"><button @click="openNode(row)">{{ row.name }}</button><span class="muted">计划完成 {{ row.planEnd }}</span><p v-if="row.overdueReason">延期说明：{{ row.overdueReason }}</p><p v-if="row.blockedReason">受阻原因：{{ row.blockedReason }}</p><p v-if="!row.overdueReason && !row.blockedReason" class="muted">尚未记录具体原因，可进入工作项查看。</p></article></section>
        <section class="surface report-section"><h2>下周到期 <span>{{ content.nextWeekDueNodes?.length || 0 }}</span></h2><div v-if="!content.nextWeekDueNodes?.length" class="empty">下周暂无到期工作项。</div><el-table v-else :data="content.nextWeekDueNodes" @row-click="openNode"><el-table-column prop="name" label="工作项" min-width="180"><template #default="scope"><el-button link type="primary" @click.stop="openNode(scope.row)">{{ scope.row.name }}</el-button></template></el-table-column><el-table-column prop="planEnd" label="计划完成" width="130" /></el-table></section>
        <section class="surface report-section"><h2>风险与说明</h2><el-input v-model="note" type="textarea" :rows="5" :readonly="!canWrite || busy" placeholder="补充本周风险、需要协调的事项和下一步" /><el-alert v-if="mutationError" :title="mutationError" type="error" :closable="false" class="note-error" /><template v-if="recoveredDraft"><h3>未保存的说明已保留</h3><p class="muted">该周报已由他人发布。以下文字未写入快照，可以复制后另行处理。</p><el-input :model-value="recoveredDraft" type="textarea" :rows="4" readonly /></template><div v-if="canWrite" class="form-actions"><span v-if="dirty" class="muted">有尚未保存的修改</span><el-button :loading="busy" @click="save">保存说明</el-button><el-button type="primary" :loading="busy" @click="publish">发布周报</el-button></div><p v-else class="muted">{{ report.status === 'PUBLISHED' ? '已发布内容保留发布时快照。' : '仅项目经理和管理员可维护草稿，其他成员只读。' }}</p></section>
        <section class="surface report-section"><el-collapse><el-collapse-item title="历史工时参考" name="hours"><p class="muted">保留既有工时口径，当前界面不要求录工时。</p><div class="hours-grid"><div><strong>{{ content.actualDays ?? 0 }}</strong><span>本周实际人天</span></div><div><strong>{{ content.plannedDays ?? 0 }}</strong><span>本周计划人天</span></div></div></el-collapse-item></el-collapse></section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page-actions>.el-select{width:200px}.section-gap{margin-bottom:20px}.report-grid{display:grid;gap:20px}.report-section h2{margin:0 0 18px}.report-section h2 span{font-weight:400;font-size:15px;color:var(--muted);margin-left:8px}.report-node{display:grid;grid-template-columns:1fr auto;gap:8px 20px;padding:16px 0;border-top:1px solid var(--line)}.report-node button{border:0;background:none;color:var(--blue);font:inherit;font-weight:600;text-align:left;padding:0;cursor:pointer}.report-node p{grid-column:1/-1;line-height:1.6;margin:0;white-space:pre-wrap}.hours-grid{display:flex;gap:50px}.hours-grid>div{display:grid;gap:8px}.hours-grid strong{font-size:22px}.hours-grid span{color:var(--muted)}.note-error{margin-top:14px}.form-actions{align-items:center;flex-wrap:wrap}.empty{padding:30px 15px}
@media(max-width:600px){.report-node{grid-template-columns:1fr}.page-actions>.el-select{width:100%}}
</style>
