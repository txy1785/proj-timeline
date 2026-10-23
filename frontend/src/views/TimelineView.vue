<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, Timeline, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import BugReportDialog from '../components/BugReportDialog.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'

const route=useRoute(); const router=useRouter(); const auth=useAuthStore()
const timeline=ref<Timeline>(); const members=ref<User[]>([]); const loading=ref(true)
const bugDialog=ref(false); const workItemDialog=ref(false)
const projectId=Number(route.params.id)
const membership=computed(()=>members.value.find(item=>item.id===auth.user?.id))
const isPm=computed(()=>auth.isAdmin||membership.value?.projectRole==='PM')
const pmId=computed(()=>members.value.find(item=>item.projectRole==='PM')?.id||0)
const active=computed(()=>timeline.value?.status==='ACTIVE')
const allNodes=computed(()=>[...(timeline.value?.modules.flatMap(m=>m.nodes||[])||[]),...(timeline.value?.projectWorkItems||[])])
const bounds=computed(()=>{const nodes=allNodes.value; if(!nodes.length)return {start:new Date(),days:1}; const dates=nodes.flatMap(n=>[new Date(n.planStart),new Date(n.planEnd)]); const start=new Date(Math.min(...dates.map(d=>d.getTime()))); const end=new Date(Math.max(...dates.map(d=>d.getTime()))); return {start,days:Math.max(1,(end.getTime()-start.getTime())/86400000+1)}})
function style(node:NodeItem){const start=(new Date(node.planStart).getTime()-bounds.value.start.getTime())/86400000; const width=(new Date(node.planEnd).getTime()-new Date(node.planStart).getTime())/86400000+1; return {left:`${start/bounds.value.days*100}%`,width:`${Math.max(5,width/bounds.value.days*100)}%`}}
async function load(){loading.value=true;try{timeline.value=await api<Timeline>('GET',`/projects/${projectId}/timeline`);members.value=await api<User[]>('GET',`/projects/${projectId}/members`)}catch(e){ElMessage.error((e as Error).message)}finally{loading.value=false}}
onMounted(load)
</script>
<template><div v-loading="loading"><header class="page-head"><div><h1>{{ timeline?.name || '项目时间线' }}</h1><p>项目阶段性目标与按模块分组的工作项计划。</p></div><el-button @click="router.push(`/projects/${projectId}/docs`)">项目文档</el-button><el-button type="primary" @click="router.push(`/projects/${projectId}/modules`)">模块视图</el-button><el-button v-if="!auth.isViewer&&active" type="warning" plain @click="bugDialog=true">提缺陷</el-button><el-button v-if="isPm&&active" type="primary" plain @click="workItemDialog=true">新建项目级工作项</el-button></header>
<section v-if="timeline?.stageTargets.length" class="surface" style="margin-bottom:18px"><h2>阶段性目标</h2><div style="display:flex;gap:10px;flex-wrap:wrap"><el-tag v-for="target in timeline.stageTargets" :key="target.id" size="large" type="primary">{{target.targetDate}} · {{target.name}}<template v-if="target.progress!=null"> · 达成 {{target.progress}}%</template></el-tag></div></section>
<section class="surface timeline"><div v-if="timeline?.projectWorkItems?.length" class="timeline-row"><div class="timeline-label"><strong>项目级工作项</strong><small class="muted" style="display:block;margin-top:5px">跨模块的测试、维护与缺陷修复</small></div><div class="timeline-track"><div v-for="node in timeline.projectWorkItems" :key="node.id" class="timeline-bar" :class="{overdue:node.flags.overdue,near:node.flags.near,blocked:node.flags.blocked}" :style="style(node)" @click="router.push(`/nodes/${node.id}`)"><strong>{{ node.name }}</strong> · {{ node.progress }}%<template v-if="node.deliverableTotal"> · 已提供 {{ node.deliverableProvided||0 }}/{{ node.deliverableTotal }}</template></div></div></div><div v-for="module in timeline?.modules" :key="module.id" class="timeline-row"><div class="timeline-label" style="cursor:pointer" @click="router.push(`/projects/${projectId}/modules/${module.id}`)"><strong>{{ module.name }}</strong><small class="muted" style="display:block;margin-top:5px">{{ module.ownerName }} · 进入模块</small></div><div class="timeline-track"><div v-for="node in module.nodes" :key="node.id" class="timeline-bar" :class="{overdue:node.flags.overdue,near:node.flags.near,blocked:node.flags.blocked}" :style="style(node)" @click="router.push(`/nodes/${node.id}`)"><strong>{{ node.name }}</strong> · {{ node.progress }}%<template v-if="node.deliverableTotal"> · 已提供 {{ node.deliverableProvided||0 }}/{{ node.deliverableTotal }}</template></div></div></div><div v-if="!timeline?.modules.length&&!timeline?.projectWorkItems?.length" class="empty">项目尚无模块和工作项</div></section>
<BugReportDialog v-model="bugDialog" :project-id="projectId" @saved="load" />
<WorkItemFormDialog v-model="workItemDialog" :project-id="projectId" :members="members" :default-owner-id="pmId" :default-plan-start="timeline?.planStart" :default-plan-end="timeline?.planEnd" @saved="load" /></div></template>
