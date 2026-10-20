<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { ModuleItem, NodeItem, Timeline, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import AttachmentPanel from '../components/AttachmentPanel.vue'

const route=useRoute(); const router=useRouter(); const auth=useAuthStore()
const projectId=Number(route.params.projectId); const moduleId=Number(route.params.moduleId)
const timeline=ref<Timeline>(); const module=ref<ModuleItem>(); const members=ref<User[]>([]); const dialog=ref(false); const loading=ref(true)
const form=reactive({name:'',description:'',planStart:'',planEnd:'',ownerId:0})
const membership=computed(()=>members.value.find(item=>item.id===auth.user?.id))
const canManage=computed(()=>!auth.isViewer&&(auth.isAdmin||membership.value?.projectRole==='PM'||module.value?.ownerId===auth.user?.id))

async function load(){loading.value=true;try{const value=await api<Timeline>('GET',`/projects/${projectId}/timeline`);timeline.value=value;module.value=value.modules.find(item=>item.id===moduleId);if(!module.value)throw new Error('模块不存在');members.value=await api<User[]>('GET',`/projects/${projectId}/members`)}catch(e){ElMessage.error((e as Error).message)}finally{loading.value=false}}
function openCreate(){Object.assign(form,{name:'',description:'',planStart:timeline.value?.planStart||'',planEnd:timeline.value?.planEnd||'',ownerId:module.value?.ownerId||0});dialog.value=true}
async function createNode(){if(!form.name.trim()||!form.planStart||!form.planEnd){ElMessage.warning('请填写节点名称和计划起止日期');return}if(form.planEnd<form.planStart){ElMessage.warning('计划结束日期不能早于开始日期');return}try{const node=await api<{id:number}>('POST',`/projects/${projectId}/nodes`,{moduleId,name:form.name,description:form.description,planStart:form.planStart,planEnd:form.planEnd,ownerId:form.ownerId||undefined});dialog.value=false;ElMessage.success('节点已创建');await load();router.push(`/nodes/${node.id}`)}catch(e){ElMessage.error((e as Error).message)}}
function openNode(row:NodeItem){router.push(`/nodes/${row.id}`)}
onMounted(load)
</script>

<template><div v-loading="loading"><header class="page-head"><div><el-breadcrumb separator="/"><el-breadcrumb-item :to="`/projects/${projectId}/modules`">模块视图</el-breadcrumb-item><el-breadcrumb-item>{{module?.name}}</el-breadcrumb-item></el-breadcrumb><h1 style="margin-top:14px">{{module?.name||'模块'}}</h1><p>{{module?.description||'暂无模块说明'}} · 负责人 {{module?.ownerName}}</p></div><el-button @click="router.push(`/projects/${projectId}/timeline`)">返回时间线</el-button></header>
<el-alert v-if="module&&!canManage" title="你可以查看本模块；模块负责人或项目经理可以创建和维护节点" type="info" :closable="false" style="margin-bottom:18px"/>
<section class="surface"><div style="display:flex;justify-content:space-between;align-items:center"><div><h2>模块节点</h2><p class="muted">填写本模块的工作节点、计划日期和负责人。</p></div><el-button v-if="canManage&&timeline?.status!=='ARCHIVED'" type="primary" @click="openCreate">新建节点</el-button></div><el-table :data="module?.nodes||[]" style="cursor:pointer" @row-click="openNode"><el-table-column prop="name" label="节点" min-width="180"/><el-table-column prop="ownerName" label="负责人"/><el-table-column prop="planStart" label="计划开始"/><el-table-column prop="planEnd" label="计划结束"/><el-table-column prop="status" label="状态"/><el-table-column prop="progress" label="进度"><template #default="scope">{{scope.row.progress}}%</template></el-table-column></el-table></section>
<section class="surface" style="margin-top:18px"><AttachmentPanel owner-type="MODULE" :owner-id="moduleId" :can-manage="!!canManage" :archived="timeline?.status==='ARCHIVED'" :show-category-nav="false" hint="模块负责人上传本模块的设计文档、流程图和开发过程痕迹，项目成员可下载查看。" /></section>
<el-dialog v-model="dialog" title="新建模块节点" width="580"><el-form label-position="top"><el-form-item label="节点名称" required><el-input v-model="form.name"/></el-form-item><el-form-item label="节点说明"><el-input v-model="form.description" type="textarea"/></el-form-item><div style="display:grid;grid-template-columns:1fr 1fr;gap:14px"><el-form-item label="计划开始日期" required><el-date-picker v-model="form.planStart" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item><el-form-item label="计划结束日期" required><el-date-picker v-model="form.planEnd" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item></div><el-form-item label="负责人"><el-select v-model="form.ownerId" style="width:100%"><el-option v-for="user in members.filter(item=>item.active&&item.globalRole!=='VIEWER')" :key="user.id" :label="user.name" :value="user.id"/></el-select></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="createNode">创建并进入节点</el-button></template></el-dialog></div></template>
