<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, Timeline } from '../api/types'

const route=useRoute(); const router=useRouter(); const timeline=ref<Timeline>(); const loading=ref(true)
const projectId=Number(route.params.id)
const bounds=computed(()=>{const nodes=timeline.value?.modules.flatMap(m=>m.nodes||[])||[]; if(!nodes.length)return {start:new Date(),days:1}; const dates=nodes.flatMap(n=>[new Date(n.planStart),new Date(n.planEnd)]); const start=new Date(Math.min(...dates.map(d=>d.getTime()))); const end=new Date(Math.max(...dates.map(d=>d.getTime()))); return {start,days:Math.max(1,(end.getTime()-start.getTime())/86400000+1)}})
function style(node:NodeItem){const start=(new Date(node.planStart).getTime()-bounds.value.start.getTime())/86400000; const width=(new Date(node.planEnd).getTime()-new Date(node.planStart).getTime())/86400000+1; return {left:`${start/bounds.value.days*100}%`,width:`${Math.max(5,width/bounds.value.days*100)}%`}}
async function load(){try{timeline.value=await api<Timeline>('GET',`/projects/${projectId}/timeline`)}catch(e){ElMessage.error((e as Error).message)}finally{loading.value=false}}
onMounted(load)
</script>
<template><div v-loading="loading"><header class="page-head"><div><h1>{{ timeline?.name || '项目时间线' }}</h1><p>项目阶段性目标与按模块分组的节点计划。</p></div><el-button @click="router.push(`/projects/${projectId}/modules`)">模块视图</el-button></header><section v-if="timeline?.stageTargets.length" class="surface" style="margin-bottom:18px"><h2>阶段性目标</h2><div style="display:flex;gap:10px;flex-wrap:wrap"><el-tag v-for="target in timeline.stageTargets" :key="target.id" size="large" type="primary">{{target.targetDate}} · {{target.name}}</el-tag></div></section><section class="surface timeline"><div v-for="module in timeline?.modules" :key="module.id" class="timeline-row"><div class="timeline-label" style="cursor:pointer" @click="router.push(`/projects/${projectId}/modules/${module.id}`)"><strong>{{ module.name }}</strong><small class="muted" style="display:block;margin-top:5px">{{ module.ownerName }} · 进入模块</small></div><div class="timeline-track"><div v-for="node in module.nodes" :key="node.id" class="timeline-bar" :class="{overdue:node.flags.overdue,near:node.flags.near,blocked:node.flags.blocked}" :style="style(node)" @click="router.push(`/nodes/${node.id}`)"><strong>{{ node.name }}</strong> · {{ node.progress }}%</div></div></div><div v-if="!timeline?.modules.length" class="empty">项目尚无模块和节点</div></section></div></template>
