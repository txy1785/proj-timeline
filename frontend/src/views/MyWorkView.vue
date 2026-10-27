<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { PageResponse, Project, WorkspaceManagement, WorkspaceTask, WorkspaceTaskPage } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { TASK_NEXT_ACTION, TASK_STATUS_LABELS, TASK_STATUS_TYPES } from '../utils/task'
import { daysBetween } from '../utils/date'
import { boundedPage } from '../utils/pagination'
import TaskDrawer from '../components/TaskDrawer.vue'

const auth = useAuthStore()
const app = useAppStore()
const tab = ref('tasks')
const scope = ref<'OPEN'|'DONE'|'HISTORY'>('OPEN')
const projectId = ref<number>()
const status = ref('')
const search = ref('')
const page = ref(1)
const data = ref<WorkspaceTaskPage>()
const management = ref<WorkspaceManagement>()
const projects = ref<Project[]>([])
const loading = ref(true)
const error = ref('')
const managementError = ref('')
const projectsError = ref('')
const busyId = ref(0)
const drawer = ref(false)
const activeTask = ref(0)
const activeNode = ref(0)
let requestVersion = 0
const managementCount = computed(() => (management.value?.nodeTotal || 0) + (management.value?.requestTotal || 0))
const hasManagement = computed(() => managementCount.value > 0 || auth.isAdmin || !!managementError.value)
const filtered = computed(() => !!projectId.value || !!status.value || !!search.value.trim())
async function loadTasks() {
  const version = ++requestVersion
  loading.value = true; error.value = ''
  try {
    const value = await api<WorkspaceTaskPage>('GET', '/workspace/tasks', undefined, {
      scope:scope.value, projectId:projectId.value, status:status.value || undefined,
      q:search.value.trim() || undefined, page:page.value, size:20
    })
    if (version === requestVersion) {
      const validPage = boundedPage(value.total, 20, page.value)
      if (validPage !== page.value) { page.value = validPage; await loadTasks(); return }
      data.value = value
    }
  } catch (e) { if (version === requestVersion) { error.value = (e as Error).message; data.value = undefined } }
  finally { if (version === requestVersion) loading.value = false }
}
async function loadManagement() {
  try { management.value = await api<WorkspaceManagement>('GET','/workspace/management'); managementError.value = '' }
  catch (e) { managementError.value = (e as Error).message; management.value = undefined }
}
async function loadProjects() {
  try {
    const all:Project[] = []
    for (let next = 1; ; next++) {
      const value = await api<PageResponse<Project>>('GET','/projects',undefined,{page:next,size:100})
      all.push(...value.list)
      if (all.length >= value.total || !value.list.length) break
    }
    projects.value = all; projectsError.value = ''
  } catch (e) { projectsError.value = (e as Error).message }
}
function filtersChanged() { page.value = 1; void loadTasks() }
function scopeChanged() { status.value = ''; filtersChanged() }
function clearFilters() { projectId.value = undefined; status.value = ''; search.value = ''; filtersChanged() }
function openTask(task:WorkspaceTask) { activeNode.value = task.nodeId; activeTask.value = task.id; drawer.value = true }
function canAct(task:WorkspaceTask) {
  return !auth.isViewer && task.assigneeId === auth.user?.id && task.projectStatus === 'ACTIVE'
    && task.nodeStatus !== 'DONE' && task.nodeStatus !== 'CANCELLED'
}
async function quickStatus(task:WorkspaceTask) {
  if (busyId.value || !canAct(task)) return
  busyId.value = task.id
  try {
    const action = TASK_NEXT_ACTION[task.status]
    await api('PUT',`/tasks/${task.id}/status`,{status:action.next,expectedStatus:task.status})
    ElMessage.success(`「${task.name}」已${action.label}`)
    await updated()
  } catch (e) { ElMessage.error((e as Error).message); await loadTasks() }
  finally { busyId.value = 0 }
}
async function updated() { await Promise.all([loadTasks(),loadManagement(),app.refresh()]) }
function dueText(task:WorkspaceTask) {
  if (!task.dueDate) return '未设置截止日'
  if (task.overdue) return `已逾期 ${Math.max(1,daysBetween(task.dueDate,data.value?.businessDate||task.dueDate))} 天`
  if (task.dueDate === data.value?.businessDate) return '今天到期'
  return ''
}
onMounted(() => { void Promise.all([loadTasks(),loadManagement(),loadProjects()]) })
</script>

<template>
  <div>
    <header class="page-head"><div><h1>我的工作</h1><p>先处理逾期和今天到期的任务，再推进后续交付。</p></div><el-button @click="updated">刷新</el-button></header>
    <div v-if="data" class="count-strip"><span><strong>{{data.counts.open}}</strong>项待处理</span><span><strong>{{data.counts.doing}}</strong>项进行中</span><span class="danger"><strong>{{data.counts.overdue}}</strong>项已逾期</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="我的任务" name="tasks">
        <section class="surface">
          <div class="section-head"><el-radio-group v-model="scope" @change="scopeChanged"><el-radio-button value="OPEN">待处理</el-radio-button><el-radio-button value="DONE">已完成</el-radio-button><el-radio-button value="HISTORY">全部历史</el-radio-button></el-radio-group></div>
          <div class="filters"><el-select v-model="projectId" clearable filterable placeholder="全部项目" aria-label="筛选项目" @change="filtersChanged"><el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id"/></el-select><el-select v-model="status" clearable placeholder="全部状态" aria-label="筛选任务状态" @change="filtersChanged"><el-option v-if="scope!=='DONE'" label="待完成" value="TODO"/><el-option v-if="scope!=='DONE'" label="进行中" value="DOING"/><el-option v-if="scope!=='OPEN'" label="已完成" value="DONE"/></el-select><el-input v-model="search" clearable placeholder="搜索我的任务" @keyup.enter="filtersChanged" @clear="filtersChanged"/><el-button @click="filtersChanged">搜索</el-button></div>
          <el-alert v-if="projectsError" :title="`项目筛选读取失败：${projectsError}`" type="warning" :closable="false"><el-button link @click="loadProjects">重试</el-button></el-alert>
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><el-button @click="loadTasks">重新读取任务</el-button></el-alert>
          <el-skeleton v-if="loading&&!data" :rows="6" animated/>
          <div v-else v-loading="loading" class="personal-task-list">
            <div v-if="data?.list.length" class="personal-task-head"><span>任务与所属交付</span><span>截止日期</span><span>状态</span><span>下一步</span></div>
            <article v-for="task in data?.list||[]" :key="task.id" class="personal-task-row">
              <div class="personal-task-title"><button class="task-name-link" @click="openTask(task)">{{task.name}} <span aria-hidden="true">›</span></button><div class="task-context"><RouterLink :to="`/projects/${task.projectId}/overview`">{{task.projectName}}</RouterLink><span> / </span><RouterLink :to="`/nodes/${task.nodeId}`">{{task.nodeName}}</RouterLink><small v-if="task.projectStatus==='ARCHIVED'"> · 已归档</small><small v-else-if="task.nodeStatus==='CANCELLED'"> · 工作项已取消</small></div></div>
              <div :class="{danger:task.overdue}"><span>{{task.dueDate||'未设置截止日'}}</span><small v-if="task.dueDate" :class="task.overdue?'danger':dueText(task)?'warning':'muted'">{{dueText(task)}}</small></div>
              <div><el-tag :type="TASK_STATUS_TYPES[task.status]" size="small">{{TASK_STATUS_LABELS[task.status]}}</el-tag></div>
              <div><el-button v-if="canAct(task)" :type="task.status==='TODO'?'primary':undefined" :plain="task.status!=='TODO'" :loading="busyId===task.id" :disabled="!!busyId" @click="quickStatus(task)">{{TASK_NEXT_ACTION[task.status].label}}</el-button><span v-else class="muted">只读</span></div>
            </article>
            <div v-if="data&&!data.list.length&&!error" class="empty"><h2>{{filtered?'当前筛选下没有结果':scope==='OPEN'?'当前没有分配给你的待处理任务':scope==='DONE'?'暂无已完成任务':'暂无历史任务'}}</h2><p v-if="managementCount">另有 {{managementCount}} 项管理事项待查看。</p><p v-else-if="!filtered">{{projects.length?'可以进入项目了解交付安排。':'你还没有加入项目，可以联系项目经理或建立自己的项目。'}}</p><el-button v-if="filtered" @click="clearFilters">清除筛选</el-button><el-button v-if="managementCount" type="primary" @click="tab='management'">查看管理事项</el-button><RouterLink to="/projects"><el-button>查看项目</el-button></RouterLink></div>
            <el-pagination v-if="data&&data.total>20" v-model:current-page="page" :page-size="20" :total="data.total" layout="prev,pager,next,total" @current-change="loadTasks"/>
          </div>
          <p v-if="data?.list.length" class="muted list-hint">点击任务名称，查看说明、进展记录和附件。完成任务后，工作项仍由负责人确认完成。</p>
        </section>
      </el-tab-pane>
      <el-tab-pane v-if="hasManagement" :label="`管理事项${managementCount?' · '+managementCount:''}`" name="management">
        <el-alert v-if="managementError" :title="managementError" type="error" :closable="false"><el-button @click="loadManagement">重试</el-button></el-alert>
        <section v-if="management" class="surface"><h2>需要处理的交付风险 <span class="muted">{{management.nodeTotal}}</span></h2><article v-for="node in management.nodes" :key="node.id" class="risk-panel" :class="{blocked:node.flags.blocked&&!node.flags.overdue}"><div class="section-head"><div><RouterLink class="task-name-link" :to="`/nodes/${node.id}`">{{node.name}}</RouterLink><p class="muted">{{node.projectName}} · 负责人 {{node.ownerName}} · 计划结束 {{node.planEnd}}</p></div><div class="page-actions"><el-tag v-if="node.flags.overdue" type="danger">已延期</el-tag><el-tag v-if="node.flags.blocked" type="warning">受阻</el-tag><RouterLink :to="`/nodes/${node.id}`"><el-button type="primary" plain>查看并处理</el-button></RouterLink></div></div><p v-if="node.flags.overdue">延期说明：{{node.overdueReason||'尚未补充原因'}}</p><p v-if="node.flags.blocked">受阻原因：{{node.blockedReason||'尚未补充原因'}}</p></article><p v-if="!management.nodeTotal" class="muted">当前没有需要你处理的延期或受阻工作项。</p><p v-if="management.nodeTotal>management.nodes.length" class="muted">显示前 {{management.nodes.length}} 项，共 {{management.nodeTotal}} 项。可在项目的工作项页查看完整清单。</p><h2 style="margin-top:26px">待我审批的模块申请 <span class="muted">{{management.requestTotal}}</span></h2><div v-for="item in management.requests" :key="item.id" class="management-request"><div><strong>{{item.moduleName}}</strong><p class="muted">申请人 {{item.requesterName}} · {{item.reason||'未填写申请说明'}}</p></div><RouterLink :to="`/projects/${item.projectId}/settings?tab=requests&requestId=${item.id}`"><el-button type="primary" plain>查看变更</el-button></RouterLink></div><p v-if="!management.requestTotal" class="muted">暂无待审批申请。</p><p v-if="management.requestTotal>management.requests.length" class="muted">显示前 {{management.requests.length}} 条，共 {{management.requestTotal}} 条。请在项目设置查看完整申请。</p></section>
      </el-tab-pane>
    </el-tabs>
    <TaskDrawer v-model="drawer" :node-id="activeNode" :task-id="activeTask" @updated="updated"/>
  </div>
</template>

<style scoped>
.personal-task-head,.personal-task-row{display:grid;grid-template-columns:minmax(220px,1fr) 150px 100px 100px;gap:18px;align-items:center;padding:18px 14px}
.personal-task-head{background:#f6f8fc;color:#52617b;border-radius:6px;font-size:13px}
.personal-task-row{border-bottom:1px solid var(--line);min-height:92px}
.task-name-link{font:inherit;font-weight:600;border:0;padding:0;background:none;color:var(--blue);cursor:pointer;text-align:left;line-height:1.6}
.task-name-link span{margin-left:8px}.task-context{font-size:12px;color:var(--muted);margin-top:7px}.task-context a:hover{text-decoration:underline}.task-context small{font-size:12px}
.personal-task-row small{display:block;font-size:12px;margin-top:4px}.list-hint{font-size:12px;margin:18px 0 0}
.management-request{display:flex;align-items:center;justify-content:space-between;gap:16px;border-top:1px solid var(--line);padding:16px 0}.management-request p{margin:5px 0 0}
@media(max-width:1000px){.personal-task-head,.personal-task-row{grid-template-columns:minmax(180px,1fr) 130px 82px 90px;gap:10px;padding-inline:4px}}
@media(max-width:700px){.personal-task-head{display:none}.personal-task-row{grid-template-columns:1fr auto;gap:12px}.personal-task-title{grid-column:1/-1}.personal-task-row>div:nth-child(3){grid-column:2;grid-row:2}.personal-task-row>div:last-child{grid-column:2;grid-row:3}.personal-task-row>div:nth-child(2){grid-column:1;grid-row:2/4}.management-request{flex-wrap:wrap}}
</style>
