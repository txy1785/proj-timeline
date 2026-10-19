<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { PageResponse, Project } from '../api/types'
import { useAuthStore } from '../stores/auth'

const router = useRouter(); const auth = useAuthStore(); const projects = ref<Project[]>([]); const loading = ref(true); const dialog = ref(false)
const form = reactive({ name:'', code:'', description:'', planStart:'', planEnd:'' })
async function load(){ loading.value=true; try { projects.value=(await api<PageResponse<Project>>('GET','/projects',undefined,{size:100})).list } catch(e){ElMessage.error((e as Error).message)} finally{loading.value=false} }
function openCreate(){Object.assign(form,{name:'',code:'',description:'',planStart:'',planEnd:''});dialog.value=true}
async function create(){if(!form.name.trim()||!form.code.trim()||!form.planStart){ElMessage.warning('请填写项目名称、项目代号和项目开始日期');return}if(form.planEnd&&form.planEnd<form.planStart){ElMessage.warning('项目完成日期不能早于开始日期');return}try { const project=await api<Project>('POST','/projects',form); dialog.value=false; await load(); router.push(`/projects/${project.id}/settings`) } catch(e){ElMessage.error((e as Error).message)} }
onMounted(load)
</script>
<template><div><header class="page-head"><div><h1>项目总览</h1><p>当前可见项目的进度、风险与交付状态。</p></div><el-button v-if="!auth.isViewer" type="primary" @click="openCreate">新建项目</el-button></header>
<div v-loading="loading" class="grid project-grid"><article v-for="project in projects" :key="project.id" class="surface project-card" @click="router.push(`/projects/${project.id}/timeline`)"><div style="display:flex;justify-content:space-between"><div><el-tag :type="project.status==='ARCHIVED'?'info':'success'" size="small">{{ project.status==='ARCHIVED'?'已归档':'进行中' }}</el-tag><h2>{{ project.name }}</h2><span class="muted">{{ project.code }}</span></div><el-progress type="circle" :percentage="project.progress" :width="72" :stroke-width="7" /></div><div class="metric-row"><span class="metric"><strong>{{ project.stats.done }}/{{ project.stats.total }}</strong>完成节点</span><span class="metric"><strong class="danger">{{ project.stats.overdue }}</strong>延期</span><span class="metric"><strong class="purple">{{ project.stats.blocked }}</strong>受阻</span><span class="metric"><strong class="warning">{{ project.stats.near }}</strong>临近</span></div></article><div v-if="!loading&&!projects.length" class="surface empty">暂无可见项目</div></div>
<el-dialog v-model="dialog" title="新建项目" width="520"><el-form label-position="top"><el-form-item label="项目名称" required><el-input v-model="form.name" /></el-form-item><el-form-item label="项目代号" required><el-input v-model="form.code" /></el-form-item><el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item><div style="display:grid;grid-template-columns:1fr 1fr;gap:12px"><el-form-item label="项目开始日期（必填）" required><el-date-picker v-model="form.planStart" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item><el-form-item label="项目完成日期（可选）"><el-date-picker v-model="form.planEnd" value-format="YYYY-MM-DD" clearable style="width:100%" /></el-form-item></div></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template></el-dialog></div></template>
