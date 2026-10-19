<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { ModuleItem } from '../api/types'
import { useAuthStore } from '../stores/auth'
const route=useRoute(); const router=useRouter(); const auth=useAuthStore(); const modules=ref<ModuleItem[]>([]); const id=Number(route.params.id)
async function load(){try{modules.value=await api<ModuleItem[]>('GET',`/projects/${id}/modules`)}catch(e){ElMessage.error((e as Error).message)}} onMounted(load)
</script>
<template><div><header class="page-head"><div><h1>模块视图</h1><p>进入模块填写工作节点、计划日期和负责人。</p></div><el-button @click="router.push(`/projects/${id}/settings`)">项目设置</el-button></header><div class="grid project-grid"><article v-for="item in modules" :key="item.id" class="surface project-card" @click="router.push(`/projects/${id}/modules/${item.id}`)" ><div style="display:flex;justify-content:space-between;align-items:center"><div><div style="display:flex;align-items:center;gap:8px"><h2>{{item.name}}</h2><el-tag v-if="item.ownerId===auth.user?.id" size="small">我的模块</el-tag></div><span class="muted">负责人 · {{item.ownerName}}</span></div><el-progress type="dashboard" :percentage="item.progress||0" :width="78" /></div><div class="metric-row"><span class="metric"><strong>{{item.nodeCount}}</strong>节点</span><span class="metric"><strong class="danger">{{item.overdueCount}}</strong>延期</span><span class="metric"><strong>进入</strong>填写节点</span></div></article></div></div></template>
