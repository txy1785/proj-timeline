<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { NodeItem, PageResponse, WorkspaceTask, WorkspaceTaskPage } from '../api/types'
import { NODE_STATUS_LABELS } from '../utils/workItem'
import { TASK_STATUS_LABELS } from '../utils/task'
import TaskDrawer from '../components/TaskDrawer.vue'
import { boundedPage } from '../utils/pagination'

interface Person { id:number;name:string;activeNodeCount:number;overdueNodeCount:number;openTaskCount:number;weekDueCount:number;activeEstimateDays:number|null;weekDueEstimateDays:number|null }
interface Workload { weekStart:string;weekEnd:string;people:Person[] }
type DetailMode='nodes-active'|'nodes-overdue'|'tasks-open'|'tasks-week'|'tasks-plan'
const week=ref('')
const data=ref<Workload>()
const loading=ref(true)
const error=ref('')
const dialog=ref(false)
const person=ref<Person>()
const mode=ref<DetailMode>('tasks-open')
const detailPage=ref(1)
const detailLoading=ref(false)
const detailError=ref('')
const nodeData=ref<PageResponse<NodeItem>>()
const taskData=ref<WorkspaceTaskPage>()
const taskDrawer=ref(false)
const activeNode=ref(0)
const activeTask=ref(0)
let loadVersion=0
let detailVersion=0
const isTasks=computed(()=>mode.value.startsWith('tasks'))
const detailTotal=computed(()=>(isTasks.value?taskData.value?.total:nodeData.value?.total)||0)
const labels:Record<DetailMode,string>={'nodes-active':'进行中工作项','nodes-overdue':'延期工作项','tasks-open':'全部未完成任务','tasks-week':'本周到期的未完成任务','tasks-plan':'本周计划任务（含已完成）'}
async function load(){
  const version=++loadVersion
  loading.value=true;error.value=''
  try{const value=await api<Workload>('GET','/workload',undefined,week.value?{week:week.value}:undefined);if(version===loadVersion)data.value=value}
  catch(e){if(version===loadVersion){error.value=(e as Error).message;data.value=undefined}}
  finally{if(version===loadVersion)loading.value=false}
}
function showDetails(value:Person,key:DetailMode){person.value=value;mode.value=key;detailPage.value=1;nodeData.value=undefined;taskData.value=undefined;dialog.value=true;void loadDetails()}
async function loadDetails(){
  if(!person.value)return
  const version=++detailVersion
  detailLoading.value=true;detailError.value=''
  try{
    if(isTasks.value){
      const value=await api<WorkspaceTaskPage>('GET','/workspace/tasks',undefined,{scope:mode.value==='tasks-plan'?'WORKLOAD_PLAN':'WORKLOAD',assigneeId:person.value.id,dueWeek:['tasks-week','tasks-plan'].includes(mode.value)?data.value?.weekStart:undefined,page:detailPage.value,size:20})
      if(version===detailVersion){
        const validPage=boundedPage(value.total,20,detailPage.value)
        if(validPage!==detailPage.value){detailPage.value=validPage;await loadDetails();return}
        taskData.value=value
      }
    }else{
      const value=await api<PageResponse<NodeItem>>('GET','/workspace/work-items',undefined,{ownerId:person.value.id,scope:mode.value==='nodes-active'?'ACTIVE':'OVERDUE',page:detailPage.value,size:20})
      if(version===detailVersion){
        const validPage=boundedPage(value.total,20,detailPage.value)
        if(validPage!==detailPage.value){detailPage.value=validPage;await loadDetails();return}
        nodeData.value=value
      }
    }
  }catch(e){if(version===detailVersion){detailError.value=(e as Error).message;nodeData.value=undefined;taskData.value=undefined}}
  finally{if(version===detailVersion)detailLoading.value=false}
}
function openTask(task:WorkspaceTask){activeNode.value=task.nodeId;activeTask.value=task.id;taskDrawer.value=true}
async function updated(){await load();if(dialog.value)await loadDetails()}
onMounted(load)
</script>
<template>
  <div><header class="page-head"><div><h1>人员负载</h1><p v-if="data">{{data.weekStart}} 至 {{data.weekEnd}} · 当前账号可读取的项目。点击数字查看对应工作。</p><p v-else>按可读项目查看责任与任务安排。</p></div><el-date-picker v-model="week" value-format="YYYY-MM-DD" placeholder="选择一周中的任意日期" aria-label="选择负载周" @change="load"/></header>
    <section class="surface"><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button @click="load">重试</el-button></el-alert><el-skeleton v-if="loading&&!data" :rows="6" animated/><div v-else v-loading="loading"><el-table :data="data?.people||[]" empty-text="当前范围暂无人员"><el-table-column prop="name" label="人员" fixed min-width="90"/><el-table-column label="进行中工作项" min-width="120"><template #default="{row}"><el-button v-if="row.activeNodeCount" link type="primary" @click="showDetails(row,'nodes-active')">{{row.activeNodeCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="延期工作项" min-width="110"><template #default="{row}"><el-button v-if="row.overdueNodeCount" link type="danger" @click="showDetails(row,'nodes-overdue')">{{row.overdueNodeCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="未完成任务" min-width="105"><template #default="{row}"><el-button v-if="row.openTaskCount" link type="primary" @click="showDetails(row,'tasks-open')">{{row.openTaskCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="本周到期" min-width="100"><template #default="{row}"><el-button v-if="row.weekDueCount" link type="primary" @click="showDetails(row,'tasks-week')">{{row.weekDueCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="全部未完成人天" min-width="135"><template #default="{row}"><el-button v-if="row.openTaskCount" link @click="showDetails(row,'tasks-open')">{{row.activeEstimateDays??0}}</el-button><span v-else>{{row.activeEstimateDays??0}}</span></template></el-table-column><el-table-column label="本周计划人天" min-width="125"><template #default="{row}"><el-button link @click="showDetails(row,'tasks-plan')">{{row.weekDueEstimateDays??0}}</el-button></template></el-table-column></el-table><p class="muted workload-note">人天为已填写的计划估算汇总。未估算任务不会自动算作 1 人天；汇总为 0 不代表没有工作。本周计划人天包含本周到期的已完成任务。</p></div></section>
    <el-dialog v-model="dialog" :title="(person?.name||'')+' · '+labels[mode]" width="1000"><p class="muted">仅显示当前账号可读取的项目；归档或已关闭工作保持只读。{{['tasks-week','tasks-plan'].includes(mode)?'到期范围：'+(data?.weekStart||'')+'—'+(data?.weekEnd||''):''}}</p><el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button @click="loadDetails">重试</el-button></el-alert><div v-loading="detailLoading"><el-table v-if="isTasks" :data="taskData?.list||[]" empty-text="当前范围暂无任务"><el-table-column label="任务" min-width="180"><template #default="{row}"><el-button link type="primary" @click="openTask(row)">{{row.name}}</el-button></template></el-table-column><el-table-column prop="projectName" label="项目" min-width="150"/><el-table-column prop="nodeName" label="工作项" min-width="150"/><el-table-column label="截止日期" width="120"><template #default="{row}">{{row.dueDate||'未设置'}}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}">{{TASK_STATUS_LABELS[row.status as keyof typeof TASK_STATUS_LABELS]}}</template></el-table-column><el-table-column label="计划人天" width="100"><template #default="{row}">{{row.estimateDays??'未估算'}}</template></el-table-column></el-table><el-table v-else :data="nodeData?.list||[]" empty-text="当前范围暂无工作项"><el-table-column label="工作项" min-width="180"><template #default="{row}"><RouterLink :to="'/nodes/'+row.id"><el-button link type="primary">{{row.name}}</el-button></RouterLink></template></el-table-column><el-table-column prop="projectName" label="项目" min-width="150"/><el-table-column prop="planEnd" label="计划结束" width="120"/><el-table-column label="状态" width="110"><template #default="{row}">{{NODE_STATUS_LABELS[row.status as keyof typeof NODE_STATUS_LABELS]}}</template></el-table-column><el-table-column label="进度" width="90"><template #default="{row}">{{row.progress}}%</template></el-table-column></el-table></div><el-pagination v-if="detailTotal>20" v-model:current-page="detailPage" :page-size="20" :total="detailTotal" layout="prev,pager,next,total" @current-change="loadDetails"/></el-dialog>
    <TaskDrawer v-model="taskDrawer" :node-id="activeNode" :task-id="activeTask" @updated="updated"/>
  </div>
</template>
<style scoped>.workload-note{font-size:12px;margin:18px 0 0}</style>
