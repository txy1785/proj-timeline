<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { PageResponse, Project } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { boundedPage } from '../utils/pagination'
import { scheduleVerdict } from '../utils/progress'

const router=useRouter()
const auth=useAuthStore()
function cardVerdict(project:Project){return scheduleVerdict({total:project.stats.total,done:project.stats.done,behind:project.stats.overdue,expected:0,donePercent:project.progress})}
const app=useAppStore()
const data=ref<PageResponse<Project>>()
const loading=ref(true)
const error=ref('')
const status=ref('ACTIVE')
const search=ref('')
const page=ref(1)
const dialog=ref(false)
const saving=ref(false)
let version=0
const form=reactive({name:'',code:'',description:'',planStart:'',planEnd:''})
const hasFilter=computed(()=>!!search.value.trim())
async function load(){
  const current=++version
  loading.value=true;error.value=''
  try{
    const value=await api<PageResponse<Project>>('GET','/projects',undefined,{page:page.value,size:12,status:status.value,q:search.value.trim()||undefined})
    if(current===version){
      const validPage=boundedPage(value.total,12,page.value)
      if(validPage!==page.value){page.value=validPage;await load();return}
      data.value=value
    }
  }catch(e){if(current===version){error.value=(e as Error).message;data.value=undefined}}
  finally{if(current===version)loading.value=false}
}
function filtersChanged(){page.value=1;void load()}
async function openCreate(){
  Object.assign(form,{name:'',code:'',description:'',planStart:app.businessDate,planEnd:''});dialog.value=true
  await app.ensure()
  if(dialog.value&&!form.planStart)form.planStart=app.businessDate
}
async function create(){
  if(saving.value)return
  if(!form.name.trim()||!form.code.trim()||!form.planStart){ElMessage.warning('请填写项目名称、代号和开始日期');return}
  if(form.planEnd&&form.planEnd<form.planStart){ElMessage.warning('项目完成日期不能早于开始日期');return}
  saving.value=true
  try{
    const project=await api<Project>('POST','/projects',{...form,name:form.name.trim(),code:form.code.trim()})
    dialog.value=false
    ElMessage.success('项目已创建，接下来建立第一项交付')
    await router.push('/projects/'+project.id+'/overview')
  }catch(e){ElMessage.error((e as Error).message)}
  finally{saving.value=false}
}
onMounted(load)
</script>

<template>
  <div>
    <header class="page-head"><div><h1>项目总览</h1><p>先了解交付安排，再进入项目推进工作。</p></div><el-button v-if="!auth.isViewer" type="primary" @click="openCreate">新建项目</el-button></header>
    <section class="filters"><el-radio-group v-model="status" @change="filtersChanged"><el-radio-button value="ACTIVE">活跃项目</el-radio-button><el-radio-button value="ARCHIVED">已归档</el-radio-button><el-radio-button value="ALL">全部</el-radio-button></el-radio-group><el-input v-model="search" clearable placeholder="搜索项目名称或代号" @keyup.enter="filtersChanged" @clear="filtersChanged"/><el-button @click="filtersChanged">搜索</el-button></section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><el-button @click="load">重试</el-button></el-alert>
    <el-skeleton v-if="loading&&!data" :rows="7" animated class="surface"/>
    <div v-else v-loading="loading" class="grid project-grid">
      <article v-for="project in data?.list||[]" :key="project.id" class="surface project-card">
        <div class="project-card-head"><el-tag :type="project.status==='ARCHIVED'?'info':'primary'" size="small">{{project.status==='ARCHIVED'?'已归档':'进行中'}}</el-tag><span class="muted">{{project.code}}</span></div>
        <h2><RouterLink :to="'/projects/'+project.id+'/overview'">{{project.name}}</RouterLink></h2>
        <p class="project-description">{{project.description||'尚未填写项目说明'}}</p>
        <div class="project-fact"><span>项目经理</span><strong>{{project.pmName||'进入项目查看'}}</strong></div>
        <div class="project-fact"><span>计划完成</span><strong>{{project.planEnd||'完成日期未定'}}</strong></div>
        <div v-if="project.stats.total" class="project-progress"><el-progress :percentage="project.progress" :stroke-width="6" :show-text="false"/><span class="muted">{{project.stats.done}} / {{project.stats.total}} 项功能已交付</span><el-tag :type="cardVerdict(project).tone" size="small">{{cardVerdict(project).label}}</el-tag></div>
        <p v-else class="muted">尚未建立工作项</p>
        <div class="project-risks"><RouterLink v-if="project.stats.overdue" class="danger" :to="'/projects/'+project.id+'/work-items?risk=overdue'">{{project.stats.overdue}} 项延期 ›</RouterLink><RouterLink v-if="project.stats.blocked" class="purple" :to="'/projects/'+project.id+'/work-items?risk=blocked'">{{project.stats.blocked}} 项受阻 ›</RouterLink><RouterLink v-if="project.stats.near" class="warning" :to="'/projects/'+project.id+'/work-items?risk=near'">{{project.stats.near}} 项临近 ›</RouterLink><span v-if="!project.stats.overdue&&!project.stats.blocked&&!project.stats.near" class="muted">{{project.stats.total?'当前没有延期、受阻或临近项':'从首个工作项开始安排交付'}}</span></div>
        <RouterLink class="project-enter" :to="'/projects/'+project.id+'/overview'">进入项目 <span aria-hidden="true">→</span></RouterLink>
      </article>
    </div>
    <section v-if="data&&!data.list.length&&!error" class="surface empty"><h2>{{hasFilter?'没有匹配的项目':status==='ARCHIVED'?'暂无归档项目':'暂无可见项目'}}</h2><p>{{hasFilter?'试试其他关键词，或清除筛选。':auth.isViewer?'项目建立后即可在这里查看交付进度。':'可以建立自己的项目，或联系项目经理把你加入现有项目。'}}</p><el-button v-if="hasFilter" @click="search='';filtersChanged()">清除搜索</el-button><el-button v-else-if="!auth.isViewer&&status!=='ARCHIVED'" type="primary" @click="openCreate">建立第一个项目</el-button></section>
    <el-pagination v-if="data&&data.total>12" v-model:current-page="page" :page-size="12" :total="data.total" layout="prev,pager,next,total" @current-change="load"/>
    <el-dialog v-model="dialog" title="新建项目" width="560" :close-on-click-modal="false"><p class="muted">先明确交付内容与开始日期，成员和工作安排可以随后补充。</p><el-form label-position="top" @submit.prevent="create"><el-form-item label="项目名称" required><el-input v-model="form.name" maxlength="100" placeholder="例如：商城首版上线"/></el-form-item><el-form-item label="项目代号" required><el-input v-model="form.code" maxlength="50" placeholder="例如：MALL-2026"/></el-form-item><el-form-item label="项目说明（可选）"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="这个项目要交付什么？"/></el-form-item><div class="form-grid"><el-form-item label="开始日期" required><el-date-picker v-model="form.planStart" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item><el-form-item label="完成日期（可选）"><el-date-picker v-model="form.planEnd" value-format="YYYY-MM-DD" clearable style="width:100%"/></el-form-item></div></el-form><template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="create">创建并安排工作</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.project-card{cursor:default;display:flex;flex-direction:column;gap:3px}.project-card h2 a:hover{color:var(--blue)}.project-card-head{display:flex;justify-content:space-between;gap:10px;font-size:12px}
.project-description{min-height:44px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.project-fact{display:flex;justify-content:space-between;gap:10px;margin-top:9px;font-size:13px}.project-fact>span{color:var(--muted)}.project-fact strong{font-weight:500}
.project-progress{margin-top:18px}.project-progress>span{font-size:12px}.project-progress .el-tag{margin-left:8px}.project-risks{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0;font-size:12px;min-height:20px}
.project-enter{display:flex;justify-content:space-between;color:var(--blue);border-top:1px solid var(--line);padding-top:12px;margin-top:auto}
</style>
