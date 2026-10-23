<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { ModuleItem, NodeItem, Timeline, User, WorkItemType } from '../api/types'
import { useAuthStore } from '../stores/auth'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import ModuleFormDialog from '../components/ModuleFormDialog.vue'
import ChangeRequestDialog from '../components/ChangeRequestDialog.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'
import { WORK_ITEM_TYPE_LABELS, WORK_ITEM_TYPE_TYPES } from '../utils/workItem'

const route=useRoute(); const router=useRouter(); const auth=useAuthStore()
const projectId=Number(route.params.projectId); const moduleId=Number(route.params.moduleId)
const timeline=ref<Timeline>(); const module=ref<ModuleItem>(); const members=ref<User[]>([]); const moduleDialog=ref(false); const workItemDialog=ref(false); const loading=ref(true)
const requestDialog=ref(false); const requestSubmitted=ref(false)
const membership=computed(()=>members.value.find(item=>item.id===auth.user?.id))
const canManage=computed(()=>!auth.isViewer&&(auth.isAdmin||membership.value?.projectRole==='PM'||module.value?.ownerId===auth.user?.id))
const isPm=computed(()=>auth.isAdmin||membership.value?.projectRole==='PM')

async function load(){loading.value=true;try{const value=await api<Timeline>('GET',`/projects/${projectId}/timeline`);timeline.value=value;module.value=value.modules.find(item=>item.id===moduleId);if(!module.value)throw new Error('模块不存在');members.value=await api<User[]>('GET',`/projects/${projectId}/members`)}catch(e){ElMessage.error((e as Error).message)}finally{loading.value=false}}
function openNode(row:NodeItem){router.push(`/nodes/${row.id}`)}
function openCreate(){workItemDialog.value=true}
function openEditModule(){moduleDialog.value=true}
async function removeModule(){try{await ElMessageBox.confirm(`确认删除模块「${module.value?.name}」？含工作项的模块不能删除。`,'删除模块',{type:'warning'})}catch{return}try{await api<void>('DELETE',`/modules/${moduleId}`);ElMessage.success('模块已删除');router.push(`/projects/${projectId}/modules`)}catch(e){ElMessage.error((e as Error).message)}}
onMounted(load)
</script>

<template><div v-loading="loading"><header class="page-head"><div><el-breadcrumb separator="/"><el-breadcrumb-item :to="`/projects/${projectId}/modules`">模块视图</el-breadcrumb-item><el-breadcrumb-item>{{module?.name}}</el-breadcrumb-item></el-breadcrumb><h1 style="margin-top:14px">{{module?.name||'模块'}}</h1><p>{{module?.description||'暂无模块说明'}} · 负责人 {{module?.ownerName}}</p></div><el-button @click="router.push(`/projects/${projectId}/modules`)">返回模块视图</el-button><el-button @click="router.push(`/projects/${projectId}/timeline`)">项目时间线</el-button><el-button v-if="!canManage&&!auth.isViewer&&timeline?.status!=='ARCHIVED'" type="primary" plain @click="requestDialog=true">申请变更模块</el-button><el-tag v-if="requestSubmitted" type="warning" style="margin-left:8px">变更申请待审批</el-tag>
<el-button v-if="isPm&&timeline?.status!=='ARCHIVED'" @click="openEditModule">编辑模块</el-button><el-button v-if="isPm&&timeline?.status!=='ARCHIVED'" type="danger" plain @click="removeModule">删除模块</el-button></header>
<el-alert v-if="module&&!canManage" title="你可以查看本模块并提交变更申请；模块负责人或项目经理可以直接创建和维护工作项" type="info" :closable="false" style="margin-bottom:18px"/>
<section class="surface"><div style="display:flex;justify-content:space-between;align-items:center"><div><h2>模块工作项</h2><p class="muted">填写本模块的工作项、类型、计划日期和负责人。</p></div><el-button v-if="canManage&&timeline?.status!=='ARCHIVED'" type="primary" @click="openCreate">新建工作项</el-button></div><el-table :data="module?.nodes||[]" style="cursor:pointer" @row-click="openNode"><el-table-column prop="name" label="工作项" min-width="180"/><el-table-column label="类型" width="110"><template #default="scope"><el-tag :type="WORK_ITEM_TYPE_TYPES[scope.row.type as WorkItemType]" size="small">{{WORK_ITEM_TYPE_LABELS[scope.row.type as WorkItemType]}}</el-tag></template></el-table-column><el-table-column prop="ownerName" label="负责人"/><el-table-column prop="planStart" label="计划开始"/><el-table-column prop="planEnd" label="计划结束"/><el-table-column prop="status" label="状态"/><el-table-column prop="progress" label="进度"><template #default="scope">{{scope.row.progress}}%</template></el-table-column></el-table></section>
<section class="surface" style="margin-top:18px"><AttachmentPanel owner-type="MODULE" :owner-id="moduleId" :can-manage="!!canManage" :archived="timeline?.status==='ARCHIVED'" :show-category-nav="false" hint="模块负责人上传本模块的设计文档、流程图和开发过程痕迹，项目成员可下载查看。" /></section>
<ModuleFormDialog v-model="moduleDialog" :project-id="projectId" :module="module" :members="members" @saved="load" />
<WorkItemFormDialog v-model="workItemDialog" :project-id="projectId" :module-id="moduleId" :members="members" :default-owner-id="module?.ownerId||0" :default-plan-start="timeline?.planStart" :default-plan-end="timeline?.planEnd" @saved="load" />
<ChangeRequestDialog v-if="module" v-model="requestDialog" :module="module" :members="members" @saved="requestSubmitted=true" /></div></template>
