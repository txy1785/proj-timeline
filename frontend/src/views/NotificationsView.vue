<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { Notification, PageResponse } from '../api/types'
import { notificationLabel } from '../utils/notification'
import { formatDateTime } from '../utils/format'
import { useAppStore } from '../stores/app'
const router=useRouter()
const app=useAppStore()
const data=ref<PageResponse<Notification>>()
const page=ref(1)
const loading=ref(true)
const error=ref('')
const busy=ref(false)
// 接口不支持服务端筛选，以下条件只作用于当前已加载的这一页。
const typeFilter=ref('')
const unreadOnly=ref(false)
const keyword=ref('')
const pageTypes=computed(()=>[...new Set([...(data.value?.list||[]).map(item=>item.type),...(typeFilter.value?[typeFilter.value]:[])])])
const hasFilter=computed(()=>!!typeFilter.value||unreadOnly.value||!!keyword.value.trim())
const visible=computed(()=>{
  const term=keyword.value.trim().toLowerCase()
  return (data.value?.list||[]).filter(item=>(!typeFilter.value||item.type===typeFilter.value)&&(!unreadOnly.value||!item.readAt)&&(!term||(item.title+' '+(item.content||'')).toLowerCase().includes(term)))
})
function clearFilters(){typeFilter.value='';unreadOnly.value=false;keyword.value=''}
let version=0
async function load(){
  const current=++version
  loading.value=true;error.value=''
  try{const value=await api<PageResponse<Notification>>('GET','/notifications',undefined,{page:page.value,size:20});if(current===version)data.value=value}
  catch(e){if(current===version){error.value=(e as Error).message;data.value=undefined}}
  finally{if(current===version)loading.value=false}
}
async function open(item:Notification){
  if(busy.value)return
  busy.value=true
  try{
    if(!item.readAt)await api('PUT','/notifications/'+item.id+'/read')
    await app.refresh()
    if(item.link?.startsWith('/')&&!item.link.startsWith('//'))await router.push(item.link)
    else await load()
  }catch(e){ElMessage.error((e as Error).message)}
  finally{busy.value=false}
}
async function readAll(){
  if(busy.value)return
  busy.value=true
  try{await api('PUT','/notifications/read-all');await Promise.all([load(),app.refresh()])}
  catch(e){ElMessage.error((e as Error).message)}
  finally{busy.value=false}
}
onMounted(load)
</script>
<template>
  <div><header class="page-head"><div><h1>通知中心</h1><p>查看分配给你的工作、项目风险、周报和模块变更提醒。</p></div><el-button :loading="busy" :disabled="!data?.list.length" @click="readAll">全部标为已读</el-button></header>
    <section class="surface"><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button @click="load">重试</el-button></el-alert><el-skeleton v-if="loading&&!data" :rows="6" animated/>
      <div v-else v-loading="loading">
        <div v-if="data?.list.length" class="notification-filters"><el-select v-model="typeFilter" clearable placeholder="全部类型" aria-label="按类型筛选当前页"><el-option v-for="type in pageTypes" :key="type" :label="notificationLabel(type)" :value="type"/></el-select><el-checkbox v-model="unreadOnly">只看未读</el-checkbox><el-input v-model="keyword" clearable placeholder="搜索项目名、标题或内容" aria-label="在当前页搜索"/><span class="muted">当前页筛选：仅作用于本页已加载的 {{data.list.length}} 条，不含其他页<template v-if="hasFilter">，匹配 {{visible.length}} 条</template></span></div>
        <button v-for="item in visible" :key="item.id" class="notification-open" :class="{unread:!item.readAt}" :disabled="busy" @click="open(item)"><span class="status-dot" :class="item.readAt?'read':'unread-dot'" aria-hidden="true"/><span class="notification-copy"><span class="notification-title"><strong>{{item.title}}</strong><el-tag v-if="!item.readAt" size="small">未读</el-tag><span v-else class="muted">已读</span></span><span v-if="item.content" class="muted notification-content">{{item.content}}</span><small class="muted">{{notificationLabel(item.type)}} · {{formatDateTime(item.createdAt)}}</small></span><span class="notification-arrow" aria-hidden="true">›</span></button>
        <div v-if="data?.list.length&&!visible.length" class="empty"><h2>本页没有匹配的通知</h2><p>筛选只查找当前页，可清除条件或翻到其他页。</p><el-button @click="clearFilters">清除筛选</el-button></div>
        <div v-if="data&&!data.list.length" class="empty"><h2>暂无通知</h2><p>收到任务指派、风险或周报提醒时会显示在这里。</p><RouterLink to="/projects"><el-button>查看项目</el-button></RouterLink></div></div>
      <el-pagination v-if="data&&data.total>20" v-model:current-page="page" :page-size="20" :total="data.total" layout="prev,pager,next,total" @current-change="load"/>
    </section>
  </div>
</template>
<style scoped>
.notification-open{width:100%;border:0;border-bottom:1px solid var(--line);background:#fff;display:flex;align-items:flex-start;gap:14px;padding:22px 10px;text-align:left;color:inherit;cursor:pointer}
.notification-open:hover{background:#f8faff}.notification-open:disabled{cursor:wait}.status-dot{flex-shrink:0;margin-top:9px}.status-dot.read{background:#c6cfdb}.unread-dot{background:var(--blue)}
.notification-copy{flex:1;min-width:0}.notification-copy>span{display:block}.notification-title{display:flex!important;align-items:center;gap:10px;flex-wrap:wrap}.notification-title strong{font-size:15px}.notification-title>.muted{font-size:12px}
.notification-filters{display:flex;align-items:center;gap:10px 14px;flex-wrap:wrap;padding:0 10px 14px;border-bottom:1px solid var(--line)}.notification-filters .el-select{width:160px}.notification-filters .el-input{width:220px}.notification-filters>.muted{font-size:12px}
.notification-content{margin:8px 0;font-size:13px}.notification-copy small{font-size:12px}.notification-arrow{color:var(--muted);font-size:22px;align-self:center}
</style>
