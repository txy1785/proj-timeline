<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { NodeItem } from '../api/types'
import { useProjectContext } from '../composables/useProjectContext'
import { useAuthStore } from '../stores/auth'
import { addDays, dateValue, daysBetween, shortDate, weekStart } from '../utils/date'
import { NODE_STATUS_LABELS } from '../utils/workItem'
import BugReportDialog from '../components/BugReportDialog.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'

const id=Number(useRoute().params.id)
const router=useRouter()
const auth=useAuthStore()
const {project,members,businessDate,isPm,canCreateWorkItem,refresh}=useProjectContext(id)
const range=ref('ALL')
const zoom=ref('WEEK')
const scroll=ref<HTMLElement>()
const workDialog=ref(false)
const bugDialog=ref(false)
const nodes=computed(()=>[...(project.value?.modules.flatMap(module=>module.nodes||[])||[]),...(project.value?.projectWorkItems||[])])
const groups=computed(()=>[
  ...(project.value?.modules.map(module=>({id:module.id,name:module.name,owner:module.ownerName,nodes:module.nodes||[]}))||[]),
  ...(project.value?.projectWorkItems?.length?[{id:0,name:'项目级工作项',owner:'跨模块事项',nodes:project.value.projectWorkItems}]:[])
])
const allowedModules=computed(()=>(project.value?.modules||[]).filter(module=>isPm.value||module.ownerId===auth.user?.id))
const pmId=computed(()=>members.value.find(member=>member.projectRole==='PM')?.id||0)
function monthNext(value:string){const dt=new Date(dateValue(value.slice(0,7)+'-01'));dt.setUTCMonth(dt.getUTCMonth()+1);return dt.toISOString().slice(0,10)}
const bounds=computed(()=>{
  const today=businessDate.value
  if(range.value==='MONTH'&&today){const start=today.slice(0,7)+'-01';return{start,end:addDays(monthNext(start),-1)}}
  if(range.value==='FOUR'&&today){const start=weekStart(addDays(today,-7));return{start,end:addDays(start,27)}}
  const values=[...nodes.value.flatMap(node=>[node.planStart,node.planEnd]),...(project.value?.stageTargets.map(target=>target.targetDate)||[])].filter(value=>Number.isFinite(dateValue(value))).sort()
  const first=values[0]||project.value?.planStart||today
  const last=values.at(-1)||first
  return{start:weekStart(first),end:addDays(weekStart(last),6)}
})
const dayCount=computed(()=>Math.max(1,daysBetween(bounds.value.start,bounds.value.end)+1))
const dayWidth=computed(()=>Math.max(760/dayCount.value,dayCount.value>730?1.5:zoom.value==='MONTH'?8:26))
const plotWidth=computed(()=>dayCount.value*dayWidth.value)
function left(value:string){return daysBetween(bounds.value.start,value)*dayWidth.value}
const periods=computed(()=>{
  if(!bounds.value.start)return[]
  const result:{start:string;end:string;label:string;x:number;width:number}[]=[]
  const yearly=dayCount.value>730
  const monthly=yearly||zoom.value==='MONTH'
  let cursor=yearly?bounds.value.start.slice(0,4)+'-01-01':monthly?bounds.value.start.slice(0,7)+'-01':weekStart(bounds.value.start)
  while(cursor<=bounds.value.end){
    const next=yearly?String(Number(cursor.slice(0,4))+1).padStart(4,'0')+'-01-01':monthly?monthNext(cursor):addDays(cursor,7)
    const start=cursor<bounds.value.start?bounds.value.start:cursor
    const end=addDays(next,-1)>bounds.value.end?bounds.value.end:addDays(next,-1)
    result.push({start,end,label:yearly?cursor.slice(0,4)+'年':monthly?cursor.slice(0,7):shortDate(start)+'—'+shortDate(end),x:left(start),width:(daysBetween(start,end)+1)*dayWidth.value})
    cursor=next
  }
  return result
})
const visibleGoals=computed(()=>(project.value?.stageTargets||[]).filter(target=>target.targetDate>=bounds.value.start&&target.targetDate<=bounds.value.end))
const todayVisible=computed(()=>!!businessDate.value&&businessDate.value>=bounds.value.start&&businessDate.value<=bounds.value.end)
function bar(node:NodeItem){
  if(node.planEnd<bounds.value.start||node.planStart>bounds.value.end)return undefined
  const start=node.planStart<bounds.value.start?bounds.value.start:node.planStart
  const end=node.planEnd>bounds.value.end?bounds.value.end:node.planEnd
  return{left:left(start)+'px',width:(daysBetween(start,end)+1)*dayWidth.value+'px'}
}
function hint(node:NodeItem){return node.name+' · '+node.ownerName+' · '+node.planStart+' 至 '+node.planEnd+' · '+NODE_STATUS_LABELS[node.status]+(node.flags.overdue?' · 已延期':'')+(node.flags.blocked?' · 受阻':'')}
async function locateToday(){
  if(!businessDate.value)return
  range.value='FOUR'
  await nextTick()
  scroll.value?.scrollTo({left:Math.max(0,left(businessDate.value)-180),behavior:'smooth'})
}
async function saved(node?:NodeItem){await refresh();if(node?.id)void router.push('/nodes/'+node.id)}
</script>

<template>
  <div>
    <header class="page-head"><div><h1>交付排期</h1><p>按功能模块查看每项交付的日期与负责人；同日使用同一日期刻度。</p></div><div class="page-actions"><el-button v-if="!auth.isViewer&&project?.status==='ACTIVE'&&nodes.some(node=>node.status!=='CANCELLED')" @click="bugDialog=true">报告 Bug</el-button><el-button v-if="canCreateWorkItem" type="primary" @click="workDialog=true">新建工作项</el-button></div></header>
    <section class="surface schedule-surface">
      <div class="section-head"><div class="page-actions"><el-select v-model="range" aria-label="排期范围" style="width:130px"><el-option label="全部排期" value="ALL"/><el-option label="本月" value="MONTH"/><el-option label="近四周" value="FOUR"/></el-select><el-select v-model="zoom" aria-label="时间线刻度" style="width:120px"><el-option label="按周" value="WEEK"/><el-option label="按月" value="MONTH"/></el-select><el-button :disabled="!businessDate" @click="locateToday">定位今天</el-button></div><div class="schedule-legend"><span><i class="not-started"/>未开始</span><span><i class="in-progress"/>进行中</span><span><i class="done"/>已交付</span><span class="danger">延期</span><span class="warning">临近</span><span class="purple">受阻</span></div></div>
      <p v-if="dayCount>730" class="muted">排期超过两年，刻度自动按年显示；可切换本月或近四周查看细节。</p>
      <div v-if="bounds.start" ref="scroll" class="schedule-scroll" tabindex="0" aria-label="项目排期，可横向滚动">
        <div class="schedule-grid" :style="{'--plot-width':plotWidth+'px','--day-width':dayWidth+'px',width:(260+plotWidth)+'px'}">
          <div class="schedule-line schedule-header"><div class="schedule-fixed schedule-column-head"><strong>工作项</strong><span>负责人</span><span>状态</span></div><div class="schedule-track"><div v-for="period in periods" :key="period.start" class="schedule-period" :style="{left:period.x+'px',width:period.width+'px'}">{{period.label}}</div></div></div>
          <div class="schedule-line schedule-goals" :style="{height:Math.max(70,visibleGoals.length*34+18)+'px'}"><div class="schedule-fixed"><strong>项目阶段目标</strong><small>{{bounds.start}} 至 {{bounds.end}}</small></div><div class="schedule-track"><div v-if="todayVisible" class="today-line" :style="{left:left(businessDate)+'px'}"><span>今天 {{shortDate(businessDate)}}</span></div><div v-for="(goal,index) in visibleGoals" :key="goal.id" class="goal-marker" :style="{left:left(goal.targetDate)+'px',top:(index*34+22)+'px'}"><span class="goal-symbol" aria-hidden="true">◆</span><el-tooltip :content="goal.targetDate+' · '+goal.name+(goal.progress!=null?' · 达成'+goal.progress+'%':'')"><span class="goal-caption" :class="{before:left(goal.targetDate)>plotWidth-240}">{{goal.name}} · {{shortDate(goal.targetDate)}}<template v-if="goal.progress!=null"> · {{goal.progress}}%</template></span></el-tooltip></div><span v-if="!visibleGoals.length" class="outside-range">此范围内没有阶段目标</span></div></div>
          <template v-for="group in groups" :key="group.id">
            <div class="schedule-group"><div class="schedule-fixed"><RouterLink v-if="group.id" :to="'/projects/'+id+'/modules/'+group.id">{{group.name}} <span class="muted">({{group.nodes.length}})</span></RouterLink><strong v-else>{{group.name}} ({{group.nodes.length}})</strong></div></div>
            <div v-for="node in group.nodes" :key="node.id" class="schedule-line schedule-work-item"><div class="schedule-fixed schedule-node-label"><RouterLink :to="'/nodes/'+node.id">{{node.name}}<small v-if="node.deliverableTotal">资料 {{node.deliverableProvided||0}}/{{node.deliverableTotal}}</small></RouterLink><span>{{node.ownerName}}</span><span>{{NODE_STATUS_LABELS[node.status]}}</span></div><div class="schedule-track"><div v-if="todayVisible" class="today-line" :style="{left:left(businessDate)+'px'}"/><el-tooltip v-if="bar(node)" :content="hint(node)" placement="top"><button class="date-bar" :class="node.status.toLowerCase().replaceAll('_','-')" :style="bar(node)" :aria-label="hint(node)" @click="router.push('/nodes/'+node.id)"><span>{{shortDate(node.planStart)}}—{{shortDate(node.planEnd)}}</span><span v-if="node.flags.overdue" class="bar-risk">延期</span><span v-if="node.flags.near" class="bar-risk">临近</span><span v-if="node.flags.blocked" class="bar-risk">受阻</span></button></el-tooltip><RouterLink v-else class="outside-range" :to="'/nodes/'+node.id">{{node.planStart}}—{{node.planEnd}} · 在当前范围之外</RouterLink></div></div>
            <div v-if="!group.nodes.length" class="schedule-line"><div class="schedule-fixed muted">尚无工作项</div><div class="schedule-track"><span class="outside-range">在该功能模块中建立首项交付</span></div></div>
          </template>
        </div>
      </div>
      <div v-if="!nodes.length" class="empty"><h2>还没有安排交付工作</h2><p>先建立工作项，再查看每项工作的排期。</p><el-button v-if="canCreateWorkItem" type="primary" @click="workDialog=true">建立首个工作项</el-button><RouterLink :to="'/projects/'+id+'/overview'"><el-button>返回项目概览</el-button></RouterLink></div>
      <p class="muted schedule-footnote">计划条覆盖开始与结束两日。每项按实际交付状态展示，延期不会自动延长计划；模块只用于功能分组。</p>
    </section>
    <BugReportDialog v-model="bugDialog" :project-id="id" @saved="refresh"/>
    <WorkItemFormDialog v-model="workDialog" :project-id="id" :members="members" :modules="allowedModules" :can-create-project-level="isPm" :business-date="businessDate" :default-owner-id="pmId" :default-plan-start="project?.planStart" @saved="saved"/>
  </div>
</template>

<style scoped>
.schedule-surface{padding:20px}.schedule-scroll{overflow:auto;max-height:72vh;border:1px solid var(--line);border-radius:7px}
.schedule-line{display:grid;grid-template-columns:260px var(--plot-width);min-height:68px;border-bottom:1px solid var(--line)}
.schedule-fixed{position:sticky;left:0;z-index:2;background:#fff;min-width:0;padding:16px 12px;border-right:1px solid var(--line)}
.schedule-fixed small{display:block;color:var(--muted);font-size:11px;margin-top:6px}.schedule-node-label,.schedule-column-head{display:grid;grid-template-columns:minmax(0,1fr) 55px 58px;gap:8px;align-items:center;font-size:13px}
.schedule-node-label>a{font-weight:500}.schedule-node-label>a:hover,.schedule-group a:hover{color:var(--blue)}.schedule-node-label>span{font-size:12px;white-space:nowrap}
.schedule-track{position:relative;background-image:linear-gradient(90deg,#f1f3f7 1px,transparent 1px);background-size:var(--day-width) 100%;min-width:0}
.schedule-header{position:sticky;top:0;z-index:5;background:#f6f8fc;min-height:52px}.schedule-header .schedule-fixed{background:#f6f8fc}
.schedule-period{position:absolute;top:0;bottom:0;display:flex;align-items:center;justify-content:center;border-left:1px solid var(--line);font-size:12px;color:#52617b;overflow:hidden;white-space:nowrap}
.schedule-group{background:#edf2fa;min-height:40px;display:flex}.schedule-group .schedule-fixed{background:#edf2fa;width:260px;padding:10px 12px;font-size:13px}
.date-bar{position:absolute;top:17px;height:35px;border:0;border-radius:5px;background:var(--blue);color:#fff;display:flex;gap:6px;align-items:center;justify-content:center;cursor:pointer;font-size:12px;padding:0 6px;overflow:hidden;white-space:nowrap;min-width:0}
.date-bar>span:first-child{overflow:hidden;text-overflow:ellipsis;min-width:0}.date-bar.not-started{background:#b5bfce;color:#243852}.date-bar.done{background:#218262}.date-bar.cancelled{background:#dae0e8;color:#56627a}
.bar-risk{font-size:11px;background:rgba(255,255,255,.95);color:#8c3d57;padding:1px 4px;border-radius:3px}
.today-line{position:absolute;top:0;bottom:0;border-left:1px dashed var(--blue);pointer-events:none;z-index:1}.today-line>span{position:absolute;top:0;left:0;transform:translateX(-50%);background:var(--blue);color:#fff;font-size:11px;padding:1px 6px;border-radius:12px;white-space:nowrap}
.goal-marker{position:absolute;z-index:2;color:var(--blue);font-size:12px}.goal-symbol{position:absolute;left:-5px;top:0}.goal-caption{margin-left:10px;white-space:nowrap;display:block;max-width:235px;overflow:hidden;text-overflow:ellipsis}.goal-caption.before{margin-left:0;transform:translateX(calc(-100% - 10px))}
.outside-range{position:absolute;left:14px;top:24px;font-size:12px;color:var(--muted)}
.schedule-legend{display:flex;gap:12px;flex-wrap:wrap;font-size:12px}.schedule-legend>span{display:flex;gap:5px;align-items:center}.schedule-legend i{height:10px;width:10px;display:inline-block;border-radius:50%;background:#b5bfce}.schedule-legend .in-progress{background:var(--blue)}.schedule-legend .done{background:#218262}
.schedule-footnote{font-size:12px;margin:16px 0 0}
@media(max-width:600px){.schedule-surface{padding:12px}.schedule-scroll{max-height:65vh}}
</style>
