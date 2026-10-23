<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { ModuleItem, NodeItem, Timeline, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { WORK_ITEM_TYPE_LABELS, WORK_ITEM_TYPE_TYPES } from '../utils/workItem'
import BugReportDialog from '../components/BugReportDialog.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'

const route=useRoute(); const router=useRouter(); const auth=useAuthStore()
const modules=ref<ModuleItem[]>([]); const timeline=ref<Timeline>(); const members=ref<User[]>([])
const loading=ref(true); const bugDialog=ref(false); const workItemDialog=ref(false)
const id=Number(route.params.id)
const membership=computed(()=>members.value.find(item=>item.id===auth.user?.id))
const isPm=computed(()=>auth.isAdmin||membership.value?.projectRole==='PM')
const pmId=computed(()=>members.value.find(item=>item.projectRole==='PM')?.id||0)
const active=computed(()=>timeline.value?.status==='ACTIVE')
async function load(){loading.value=true;try{modules.value=await api<ModuleItem[]>('GET',`/projects/${id}/modules`);timeline.value=await api<Timeline>('GET',`/projects/${id}/timeline`);members.value=await api<User[]>('GET',`/projects/${id}/members`)}catch(e){ElMessage.error((e as Error).message)}finally{loading.value=false}}
function openNode(row:NodeItem){router.push(`/nodes/${row.id}`)}
onMounted(load)
</script>
<template><div v-loading="loading"><header class="page-head"><div><h1>模块视图</h1><p>进入模块填写工作项、计划日期和负责人；跨模块事项放在项目级工作项。</p></div><el-button @click="router.push(`/projects/${id}/settings`)">项目设置</el-button><el-button v-if="!auth.isViewer&&active" type="warning" plain @click="bugDialog=true">提缺陷</el-button><el-button v-if="isPm&&active" type="primary" plain @click="workItemDialog=true">新建项目级工作项</el-button></header><div class="grid project-grid"><article v-for="item in modules" :key="item.id" class="surface project-card" @click="router.push(`/projects/${id}/modules/${item.id}`)"><div style="display:flex;justify-content:space-between;align-items:center"><div><div style="display:flex;align-items:center;gap:8px"><h2>{{item.name}}</h2><el-tag v-if="item.ownerId===auth.user?.id" size="small">我的模块</el-tag></div><span class="muted">负责人 · {{item.ownerName}}</span></div><el-progress type="dashboard" :percentage="item.progress||0" :width="78" /></div><div class="metric-row"><span class="metric"><strong>{{item.nodeCount}}</strong>工作项</span><span class="metric"><strong class="danger">{{item.overdueCount}}</strong>延期</span><span class="metric"><strong>进入</strong>填写工作项</span></div></article></div><section v-if="timeline?.projectWorkItems?.length" class="surface" style="margin-top:18px"><div style="display:flex;justify-content:space-between;align-items:center"><div><h2>项目级工作项</h2><p class="muted">跨模块的测试、维护、缺陷修复和上线保障，不属于具体功能模块。</p></div></div><el-table :data="timeline.projectWorkItems" style="cursor:pointer" @row-click="openNode"><el-table-column prop="name" label="工作项" min-width="180"/><el-table-column label="类型" width="110"><template #default="scope"><el-tag :type="WORK_ITEM_TYPE_TYPES[scope.row.type as keyof typeof WORK_ITEM_TYPE_TYPES]" size="small">{{WORK_ITEM_TYPE_LABELS[scope.row.type as keyof typeof WORK_ITEM_TYPE_LABELS]}}</el-tag></template></el-table-column><el-table-column prop="ownerName" label="负责人" width="110"/><el-table-column prop="planStart" label="计划开始" width="120"/><el-table-column prop="planEnd" label="计划结束" width="120"/><el-table-column prop="status" label="状态" width="120"/><el-table-column label="进度" width="90"><template #default="scope">{{scope.row.progress}}%</template></el-table-column></el-table></section><BugReportDialog v-model="bugDialog" :project-id="id" @saved="load" /><WorkItemFormDialog v-model="workItemDialog" :project-id="id" :members="members" :default-owner-id="pmId" :default-plan-start="timeline?.planStart" :default-plan-end="timeline?.planEnd" @saved="load" /></div></template>
