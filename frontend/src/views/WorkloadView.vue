<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import type { DeliveryWorkloadPerson, WorkspaceDeliveryPage, WorkspaceTask, WorkspaceTaskPage } from '../api/types'
import { NODE_STATUS_LABELS } from '../utils/workItem'
import { BUG_STATUS_LABELS } from '../utils/bug'
import { boundedPage } from '../utils/pagination'

const router=useRouter()

type Person=DeliveryWorkloadPerson
interface Workload { weekStart:string;weekEnd:string;people:Person[] }
type DetailMode='delivery-open'|'delivery-doing'|'delivery-overdue'|'delivery-week'|'bug-open'|'bug-overdue'
const week=ref('')
const data=ref<Workload>()
const loading=ref(true)
const error=ref('')
const dialog=ref(false)
const person=ref<Person>()
const mode=ref<DetailMode>('delivery-open')
const detailPage=ref(1)
const detailLoading=ref(false)
const detailError=ref('')
const nodeData=ref<WorkspaceDeliveryPage>()
const taskData=ref<WorkspaceTaskPage>()
let loadVersion=0
let detailVersion=0
const isTasks=computed(()=>mode.value.startsWith('bug'))
const detailTotal=computed(()=>(isTasks.value?taskData.value?.total:nodeData.value?.total)||0)
const labels:Record<DetailMode,string>={'delivery-open':'待交付工作项','delivery-doing':'进行中工作项','delivery-overdue':'延期工作项','delivery-week':'本周计划交付','bug-open':'待处理 Bug','bug-overdue':'逾期 Bug'}
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
      const value=await api<WorkspaceTaskPage>('GET','/workspace/bugs',undefined,{scope:'OPEN',assigneeId:person.value.id,overdue:mode.value==='bug-overdue'||undefined,page:detailPage.value,size:20})
      if(version===detailVersion){
        const validPage=boundedPage(value.total,20,detailPage.value)
        if(validPage!==detailPage.value){detailPage.value=validPage;await loadDetails();return}
        taskData.value=value
      }
    }else{
      const value=await api<WorkspaceDeliveryPage>('GET','/workspace/deliveries',undefined,{scope:'OPEN',ownerId:person.value.id,status:mode.value==='delivery-doing'?'IN_PROGRESS':undefined,overdue:mode.value==='delivery-overdue'||undefined,dueWeek:mode.value==='delivery-week'?data.value?.weekStart:undefined,page:detailPage.value,size:20})
      if(version===detailVersion){
        const validPage=boundedPage(value.total,20,detailPage.value)
        if(validPage!==detailPage.value){detailPage.value=validPage;await loadDetails();return}
        nodeData.value=value
      }
    }
  }catch(e){if(version===detailVersion){detailError.value=(e as Error).message;nodeData.value=undefined;taskData.value=undefined}}
  finally{if(version===detailVersion)detailLoading.value=false}
}
function openTask(task:WorkspaceTask){void router.push(`/nodes/${task.nodeId}/tasks/${task.id}`)}
onMounted(load)
</script>
<template>
  <div><header class="page-head"><div><h1>人员负载</h1><p v-if="data">{{data.weekStart}} 至 {{data.weekEnd}} · 点击数字查看对应工作。</p><p v-else>按可读项目查看交付责任与待处理 Bug。</p></div><el-date-picker v-model="week" value-format="YYYY-MM-DD" placeholder="选择一周中的任意日期" aria-label="选择负载周" @change="load"/></header>
    <section class="surface"><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button @click="load">重试</el-button></el-alert><el-skeleton v-if="loading&&!data" :rows="6" animated/><div v-else v-loading="loading"><el-table :data="data?.people||[]" empty-text="当前范围暂无人员"><el-table-column prop="name" label="人员" fixed min-width="100"/><el-table-column label="待交付工作项" min-width="125"><template #default="{row}"><el-button v-if="row.deliveryOpenCount" link type="primary" @click="showDetails(row,'delivery-open')">{{row.deliveryOpenCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="进行中" min-width="125"><template #default="{row}"><el-button v-if="row.deliveryDoingCount" link type="primary" @click="showDetails(row,'delivery-doing')">{{row.deliveryDoingCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="延期交付" min-width="125"><template #default="{row}"><el-button v-if="row.deliveryOverdueCount" link type="danger" @click="showDetails(row,'delivery-overdue')">{{row.deliveryOverdueCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="本周计划交付" min-width="125"><template #default="{row}"><el-button v-if="row.deliveryWeekDueCount" link type="primary" @click="showDetails(row,'delivery-week')">{{row.deliveryWeekDueCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="待处理 Bug" min-width="125"><template #default="{row}"><el-button v-if="row.openBugCount" link type="primary" @click="showDetails(row,'bug-open')">{{row.openBugCount}}</el-button><span v-else>0</span></template></el-table-column><el-table-column label="逾期 Bug" min-width="125"><template #default="{row}"><el-button v-if="row.overdueBugCount" link type="danger" @click="showDetails(row,'bug-overdue')">{{row.overdueBugCount}}</el-button><span v-else>0</span></template></el-table-column></el-table><p class="muted workload-note">统计当前账号可读的有效项目；工作项由负责人确认交付，Bug 独立跟踪。已交付功能的待处理 Bug 仍计入负载，归档项目保留历史。</p></div></section>
    <el-dialog v-model="dialog" :title="(person?.name||'')+' · '+labels[mode]" width="1000"><p class="muted">仅显示当前账号可读的有效项目。{{mode==='delivery-week'?'计划交付日期：'+(data?.weekStart||'')+'—'+(data?.weekEnd||''):isTasks?'关联功能已交付后，Bug 仍可按权限处理。':''}}</p><el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button @click="loadDetails">重试</el-button></el-alert><div v-loading="detailLoading"><el-table v-if="isTasks" :data="taskData?.list||[]" empty-text="当前范围暂无 Bug"><el-table-column label="Bug" min-width="180"><template #default="{row}"><el-button link type="primary" @click="openTask(row)">{{row.name}}</el-button></template></el-table-column><el-table-column prop="projectName" label="项目" min-width="150"/><el-table-column label="关联功能" min-width="150"><template #default="{row}"><RouterLink :to="'/nodes/'+(row.originNodeId||row.nodeId)">{{row.originNodeName||row.nodeName}}</RouterLink></template></el-table-column><el-table-column label="截止日期" width="120"><template #default="{row}">{{row.dueDate||'未设置'}}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}">{{BUG_STATUS_LABELS[row.status as keyof typeof BUG_STATUS_LABELS]}}</template></el-table-column></el-table><el-table v-else :data="nodeData?.list||[]" empty-text="当前范围暂无工作项"><el-table-column label="工作项" min-width="180"><template #default="{row}"><RouterLink :to="'/nodes/'+row.id"><el-button link type="primary">{{row.name}}</el-button></RouterLink></template></el-table-column><el-table-column prop="projectName" label="项目" min-width="150"/><el-table-column prop="planEnd" label="计划交付" width="120"/><el-table-column label="状态" width="110"><template #default="{row}">{{NODE_STATUS_LABELS[row.status as keyof typeof NODE_STATUS_LABELS]}}</template></el-table-column></el-table></div><el-pagination v-if="detailTotal>20" v-model:current-page="detailPage" :page-size="20" :total="detailTotal" layout="prev,pager,next,total" @current-change="loadDetails"/></el-dialog>
  </div>
</template>
<style scoped>.workload-note{font-size:12px;margin:18px 0 0}</style>
