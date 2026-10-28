<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, PageResponse, Project, WorkspaceDeliveryPage, WorkspaceManagement, WorkspaceTask, WorkspaceTaskPage } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { BUG_NEXT_ACTION, BUG_STATUS_LABELS } from '../utils/bug'
import { TASK_STATUS_LABELS, TASK_STATUS_TYPES } from '../utils/task'
import { NODE_STATUS_LABELS, NODE_STATUS_TYPES, hasWorkItemRisk } from '../utils/workItem'
import { daysBetween } from '../utils/date'
import { boundedPage } from '../utils/pagination'
const auth=useAuthStore(), app=useAppStore(), router=useRouter()
const tab=ref('deliveries'), scope=ref<'OPEN'|'DONE'|'HISTORY'>('OPEN'), projectId=ref<number>(), status=ref(''), search=ref(''), page=ref(1)
const deliveries=ref<WorkspaceDeliveryPage>(), bugs=ref<WorkspaceTaskPage>(), history=ref<WorkspaceTaskPage>()
const management=ref<WorkspaceManagement>(), projects=ref<Project[]>([])
const loading=ref(true), error=ref(''), managementError=ref(''), projectsError=ref(''), busyId=ref(0)
let requestVersion=0
const managementCount=computed(()=>(management.value?.nodeTotal||0)+(management.value?.requestTotal||0))
const hasManagement=computed(()=>managementCount.value>0||auth.isAdmin||!!managementError.value)
const filtered=computed(()=>!!projectId.value||!!status.value||!!search.value.trim())
const isDelivery=computed(()=>tab.value==='deliveries'), isHistory=computed(()=>tab.value==='history')
const data=computed(()=>isDelivery.value?deliveries.value:tab.value==='bugs'?bugs.value:history.value)
const taskRows=computed(()=>tab.value==='bugs'?bugs.value?.list||[]:history.value?.list||[])
async function loadList(){
 const version=++requestVersion, target=tab.value
 if(target==='management'){loading.value=false;return}
 loading.value=true;error.value=''
 try{
  const value=await api<WorkspaceDeliveryPage|WorkspaceTaskPage>('GET',target==='deliveries'?'/workspace/deliveries':target==='bugs'?'/workspace/bugs':'/workspace/tasks',undefined,{scope:target==='history'?'HISTORY':scope.value,legacyOnly:target==='history'?true:undefined,projectId:projectId.value,status:status.value||undefined,q:search.value.trim()||undefined,page:page.value,size:20})
  if(version!==requestVersion)return
  const valid=boundedPage(value.total,20,page.value)
  if(valid!==page.value){page.value=valid;await loadList();return}
  if(target==='deliveries')deliveries.value=value as WorkspaceDeliveryPage
  else if(target==='bugs')bugs.value=value as WorkspaceTaskPage
  else history.value=value as WorkspaceTaskPage
 }catch(e){if(version===requestVersion){error.value=(e as Error).message;if(target==='deliveries')deliveries.value=undefined;else if(target==='bugs')bugs.value=undefined;else history.value=undefined}}
 finally{if(version===requestVersion)loading.value=false}
}
async function loadManagement(){try{management.value=await api<WorkspaceManagement>('GET','/workspace/management');managementError.value=''}catch(e){managementError.value=(e as Error).message;management.value=undefined}}
async function loadProjects(){try{const all:Project[]=[];for(let next=1;;next++){const value=await api<PageResponse<Project>>('GET','/projects',undefined,{page:next,size:100});all.push(...value.list);if(all.length>=value.total||!value.list.length)break}projects.value=all;projectsError.value=''}catch(e){projectsError.value=(e as Error).message}}
function filtersChanged(){page.value=1;void loadList()}
function scopeChanged(){status.value='';filtersChanged()}
function clearFilters(){projectId.value=undefined;status.value='';search.value='';filtersChanged()}
watch(tab,()=>{scope.value='OPEN';status.value='';page.value=1;void loadList()})
function openTask(task:WorkspaceTask){void router.push(`/nodes/${task.nodeId}/tasks/${task.id}`)}
function canDeliver(node:NodeItem){return !loading.value&&!auth.isViewer&&auth.user?.active!==false&&node.ownerId===auth.user?.id&&node.projectStatus==='ACTIVE'&&(node.status==='NOT_STARTED'||node.status==='IN_PROGRESS')}
async function deliveryStatus(node:NodeItem){
 if(busyId.value||!canDeliver(node))return
 busyId.value=node.id
 const next=node.status==='NOT_STARTED'?'IN_PROGRESS':'DONE'
 try{
  if(next==='DONE'){try{await ElMessageBox.confirm(`确认「${node.name}」已达到交付要求，并已检查约定资料？${node.openBugCount?` 仍有 ${node.openBugCount} 个 Bug 待处理，交付后会继续独立跟踪。`:''}`,'确认交付',{confirmButtonText:'确认交付',cancelButtonText:'继续检查',type:'info'})}catch{return}}
  await api('POST',`/nodes/${node.id}/status`,{status:next,expectedStatus:node.status});ElMessage.success(next==='DONE'?'工作项已确认交付':'工作项已开始');await updated()
 }catch(e){ElMessage.error((e as Error).message);await loadList()}finally{busyId.value=0}
}
function canFix(task:WorkspaceTask){return !loading.value&&!auth.isViewer&&auth.user?.active!==false&&task.assigneeId===auth.user?.id&&task.projectStatus==='ACTIVE'&&(task.originNodeStatus||task.nodeStatus)!=='CANCELLED'}
async function bugStatus(task:WorkspaceTask){if(busyId.value||!canFix(task))return;busyId.value=task.id;try{const action=BUG_NEXT_ACTION[task.status];await api('PUT',`/tasks/${task.id}/status`,{status:action.next,expectedStatus:task.status});ElMessage.success(`Bug 已更新为${BUG_STATUS_LABELS[action.next]}`);await updated()}catch(e){ElMessage.error((e as Error).message);await loadList()}finally{busyId.value=0}}
async function updated(){await Promise.all([loadList(),loadManagement(),app.refresh()])}
function dueText(date?:string,overdue=false){if(!date)return '未设置截止日';if(overdue)return `已逾期 ${Math.max(1,daysBetween(date,data.value?.businessDate||app.businessDate||date))} 天`;return date===(data.value?.businessDate||app.businessDate)?'今天到期':''}
onMounted(()=>{void Promise.all([loadList(),loadManagement(),loadProjects()])})
</script>

<template>
 <div>
  <header class="page-head"><div><h1>我的工作</h1><p>推进负责的功能交付，跟进待处理 Bug。</p></div><el-button @click="updated">刷新</el-button></header>
  <div v-if="data&&tab!=='management'&&!isHistory" class="count-strip"><span><strong>{{data.counts.open}}</strong>{{isDelivery?'项待交付':'个待处理 Bug'}}</span><span><strong>{{data.counts.doing}}</strong>{{isDelivery?'项进行中':'个修复中'}}</span><span class="danger"><strong>{{data.counts.overdue}}</strong>{{isDelivery?'项已延期':'个已逾期'}}</span></div>
  <el-tabs v-model="tab"><el-tab-pane label="我的工作项" name="deliveries"/><el-tab-pane label="我的 Bug" name="bugs"/><el-tab-pane v-if="hasManagement" :label="`管理事项${managementCount?' · '+managementCount:''}`" name="management"/><el-tab-pane label="历史任务" name="history"/></el-tabs>
  <section v-if="tab!=='management'" class="surface">
   <el-alert v-if="isHistory" title="旧任务与记录保留在这里。新的功能交付和 Bug 请在前两个页签中推进。" type="info" :closable="false"/>
   <div v-else class="section-head"><el-radio-group v-model="scope" @change="scopeChanged"><el-radio-button value="OPEN">{{isDelivery?'待交付':'待处理'}}</el-radio-button><el-radio-button value="DONE">{{isDelivery?'已交付':'已解决'}}</el-radio-button><el-radio-button value="HISTORY">全部历史</el-radio-button></el-radio-group></div>
   <div class="filters"><el-select v-model="projectId" clearable filterable placeholder="全部项目" aria-label="筛选项目" @change="filtersChanged"><el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id"/></el-select><el-select v-model="status" clearable placeholder="全部状态" aria-label="筛选工作状态" @change="filtersChanged"><template v-if="isDelivery"><el-option v-if="scope!=='DONE'" label="未开始" value="NOT_STARTED"/><el-option v-if="scope!=='DONE'" label="进行中" value="IN_PROGRESS"/><el-option v-if="scope!=='OPEN'" label="已交付" value="DONE"/><el-option v-if="scope==='HISTORY'" label="已取消" value="CANCELLED"/></template><template v-else><el-option v-if="scope!=='DONE'||isHistory" :label="isHistory?'待完成':'待修复'" value="TODO"/><el-option v-if="scope!=='DONE'||isHistory" :label="isHistory?'进行中':'修复中'" value="DOING"/><el-option v-if="scope!=='OPEN'||isHistory" :label="isHistory?'已完成':'已解决'" value="DONE"/></template></el-select><el-input v-model="search" clearable :placeholder="isDelivery?'搜索我的工作项':isHistory?'搜索历史任务':'搜索我的 Bug'" aria-label="搜索我的工作" @keyup.enter="filtersChanged" @clear="filtersChanged"/><el-button @click="filtersChanged">搜索</el-button></div>
   <el-alert v-if="projectsError" :title="`项目筛选读取失败：${projectsError}`" type="warning" :closable="false"><el-button link @click="loadProjects">重试</el-button></el-alert>
   <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><el-button @click="loadList">重新读取</el-button></el-alert>
   <el-skeleton v-if="loading&&!data" :rows="6" animated/>
   <div v-else v-loading="loading" class="personal-work-list">
    <div v-if="data?.list.length" class="personal-task-head"><span>{{isDelivery?'工作项与所属项目':isHistory?'历史任务与所属工作项':'Bug 与关联功能'}}</span><span>{{isDelivery?'计划交付':'截止日期'}}</span><span>状态</span><span>下一步</span></div>
    <template v-if="isDelivery"><article v-for="node in deliveries?.list||[]" :key="node.id" class="personal-task-row personal-delivery-row"><div class="personal-task-title"><RouterLink class="task-name-link" :to="`/nodes/${node.id}`">{{node.name}} <span aria-hidden="true">›</span></RouterLink><div class="task-context"><RouterLink :to="`/projects/${node.projectId}/overview`">{{node.projectName}}</RouterLink><span> / {{node.moduleName||'项目级工作项'}}</span><small v-if="node.projectStatus==='ARCHIVED'"> · 已归档</small></div></div><div :class="{danger:node.flags.overdue}"><span>{{node.planEnd}}</span><small :class="node.flags.overdue?'danger':'muted'">{{dueText(node.planEnd,node.flags.overdue)}}</small></div><div><el-tag :type="NODE_STATUS_TYPES[node.status]" size="small">{{node.status==='DONE'?'已交付':NODE_STATUS_LABELS[node.status]}}</el-tag><small v-if="hasWorkItemRisk(node, 'blocked')" class="purple">受阻</small></div><div><el-button v-if="canDeliver(node)" :type="node.status==='NOT_STARTED'?'primary':undefined" :loading="busyId===node.id" :disabled="!!busyId" @click="deliveryStatus(node)">{{node.status==='NOT_STARTED'?'开始工作':'确认交付'}}</el-button><RouterLink v-else :to="`/nodes/${node.id}`"><el-button>查看详情</el-button></RouterLink></div></article></template>
    <template v-else><article v-for="task in taskRows" :key="task.id" class="personal-task-row personal-bug-row"><div class="personal-task-title"><button class="task-name-link" @click="openTask(task)">{{task.name}} <span aria-hidden="true">›</span></button><div class="task-context"><RouterLink :to="`/projects/${task.projectId}/overview`">{{task.projectName}}</RouterLink><span> / </span><RouterLink :to="`/nodes/${task.originNodeId||task.nodeId}`">{{task.originNodeName||task.nodeName}}</RouterLink><small v-if="task.projectStatus==='ARCHIVED'"> · 已归档</small><small v-else-if="(task.originNodeStatus||task.nodeStatus)==='DONE'"> · 功能已交付</small><small v-else-if="(task.originNodeStatus||task.nodeStatus)==='CANCELLED'"> · 工作项已取消</small></div></div><div :class="{danger:task.overdue}"><span>{{task.dueDate||'未设置截止日'}}</span><small v-if="task.dueDate" :class="task.overdue?'danger':'muted'">{{dueText(task.dueDate,task.overdue)}}</small></div><div><el-tag :type="TASK_STATUS_TYPES[task.status]" size="small">{{!isHistory||task.kind==='BUG'?BUG_STATUS_LABELS[task.status]:TASK_STATUS_LABELS[task.status]}}</el-tag></div><div><el-button v-if="!isHistory&&canFix(task)" :type="task.status==='TODO'?'primary':undefined" :loading="busyId===task.id" :disabled="!!busyId" @click="bugStatus(task)">{{BUG_NEXT_ACTION[task.status].label}}</el-button><el-button v-else @click="openTask(task)">查看记录</el-button></div></article></template>
    <div v-if="data&&!data.list.length&&!error" class="empty"><h2>{{filtered?'当前筛选下没有结果':isHistory?'暂无历史任务':isDelivery?(scope==='OPEN'?'当前没有你负责的待交付工作项':scope==='DONE'?'暂无已交付工作项':'暂无工作项记录'):(scope==='OPEN'?'当前没有分配给你的待处理 Bug':scope==='DONE'?'暂无已解决 Bug':'暂无 Bug 记录')}}</h2><p v-if="managementCount">另有 {{managementCount}} 项管理事项待查看。</p><p v-else-if="!filtered">可以进入项目查看交付安排与资料。</p><el-button v-if="filtered" @click="clearFilters">清除筛选</el-button><el-button v-if="managementCount" type="primary" @click="tab='management'">查看管理事项</el-button><RouterLink to="/projects"><el-button>查看项目</el-button></RouterLink></div>
    <el-pagination v-if="data&&data.total>20" v-model:current-page="page" :page-size="20" :total="data.total" layout="prev,pager,next,total" @current-change="loadList"/>
   </div>
   <p v-if="data?.list.length" class="muted list-hint">{{isDelivery?'工作项是一次功能交付。点击名称查看功能说明、交付资料与关联 Bug。':isHistory?'已有说明、记录和附件均保留；历史任务不参与新的交付完成率。':'Bug 独立跟踪修复；关联功能已交付后仍可按权限继续处理。'}}</p>
  </section>
  <template v-else>
        <el-alert v-if="managementError" :title="managementError" type="error" :closable="false"><el-button @click="loadManagement">重试</el-button></el-alert>
        <section v-if="management" class="surface"><h2>需要处理的交付风险 <span class="muted">{{management.nodeTotal}}</span></h2><article v-for="node in management.nodes" :key="node.id" class="risk-panel" :class="{blocked:node.flags.blocked&&!node.flags.overdue}"><div class="section-head"><div><RouterLink class="task-name-link" :to="`/nodes/${node.id}`">{{node.name}}</RouterLink><p class="muted">{{node.projectName}} · 负责人 {{node.ownerName}} · 计划结束 {{node.planEnd}}</p></div><div class="page-actions"><el-tag v-if="node.flags.overdue" type="danger">已延期</el-tag><el-tag v-if="hasWorkItemRisk(node, 'blocked')" type="warning">受阻</el-tag><RouterLink :to="`/nodes/${node.id}`"><el-button type="primary" plain>查看并处理</el-button></RouterLink></div></div><p v-if="node.flags.overdue">延期说明：{{node.overdueReason||'尚未补充原因'}}</p><p v-if="hasWorkItemRisk(node, 'blocked')">受阻原因：{{node.blockedReason||'尚未补充原因'}}</p></article><p v-if="!management.nodeTotal" class="muted">当前没有需要你处理的延期或受阻工作项。</p><p v-if="management.nodeTotal>management.nodes.length" class="muted">显示前 {{management.nodes.length}} 项，共 {{management.nodeTotal}} 项。可在项目的工作项页查看完整清单。</p><h2 style="margin-top:26px">待我审批的模块申请 <span class="muted">{{management.requestTotal}}</span></h2><div v-for="item in management.requests" :key="item.id" class="management-request"><div><strong>{{item.moduleName}}</strong><p class="muted">申请人 {{item.requesterName}} · {{item.reason||'未填写申请说明'}}</p></div><RouterLink :to="`/projects/${item.projectId}/settings?tab=requests&requestId=${item.id}`"><el-button type="primary" plain>查看变更</el-button></RouterLink></div><p v-if="!management.requestTotal" class="muted">暂无待审批申请。</p><p v-if="management.requestTotal>management.requests.length" class="muted">显示前 {{management.requests.length}} 条，共 {{management.requestTotal}} 条。请在项目设置查看完整申请。</p></section>

  </template>
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
