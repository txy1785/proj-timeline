<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { ChangeRequest, NodeItem, NodeStatus, WorkItemType } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectContext } from '../composables/useProjectContext'
import { canEditModule } from '../utils/permissions'
import { NODE_STATUS_LABELS, NODE_STATUS_TYPES, WORK_ITEM_TYPE_LABELS } from '../utils/workItem'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import ModuleFormDialog from '../components/ModuleFormDialog.vue'
import ChangeRequestDialog from '../components/ChangeRequestDialog.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'

const route = useRoute()
const projectId = Number(route.params.projectId)
const moduleId = Number(route.params.moduleId)
const router = useRouter()
const auth = useAuthStore()
const { project, members, loading, error, businessDate, isPm, refresh } = useProjectContext(projectId)
const module = computed(() => project.value?.modules.find(item => item.id === moduleId))
const active = computed(() => project.value?.status === 'ACTIVE')
const canManage = computed(() => !!module.value && active.value && canEditModule(auth.user, members.value, module.value))
const canRequest = computed(() => !!module.value && active.value && !auth.isViewer && !canManage.value)
const moduleDialog = ref(false)
const workItemDialog = ref(false)
const requestDialog = ref(false)
const pendingRequest = ref<ChangeRequest>()
let requestVersion = 0
watch([() => module.value?.id, canRequest], async () => {
  const current = ++requestVersion
  pendingRequest.value = undefined
  if (!canRequest.value) return
  try {
    const rows = await api<ChangeRequest[]>('GET', `/projects/${projectId}/change-requests`, undefined, { status: 'PENDING' })
    if (current === requestVersion) pendingRequest.value = rows.find(item => item.moduleId === moduleId && item.requestedBy === auth.user?.id)
  } catch (e) { if (current === requestVersion) ElMessage.error((e as Error).message) }
}, { immediate: true })
function openNode(node: NodeItem) { router.push(`/nodes/${node.id}`) }
function created(node: NodeItem) { void refresh(); router.push(`/nodes/${node.id}`) }
async function removeModule() {
  if (!module.value || !isPm.value || !active.value) return
  try { await ElMessageBox.confirm(`确认删除模块「${module.value.name}」？含工作项或待审批申请时不能删除。`, '删除模块', { type: 'warning' }) } catch { return }
  try { await api('DELETE', `/modules/${moduleId}`); await refresh(); ElMessage.success('模块已删除'); router.push(`/projects/${projectId}/work-items`) }
  catch (e) { ElMessage.error((e as Error).message); await refresh() }
}
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="refresh">重新加载</el-button></el-alert>
    <div v-else-if="!loading && project && !module" class="surface empty"><h2>模块已不可用</h2><p>模块可能已被删除，或不属于当前项目。</p><el-button @click="router.push(`/projects/${projectId}/work-items`)">返回工作项</el-button></div>
    <template v-if="module">
      <header class="page-head"><div><el-breadcrumb separator="/"><el-breadcrumb-item :to="`/projects/${projectId}/work-items`">工作项</el-breadcrumb-item><el-breadcrumb-item>{{ module.name }}</el-breadcrumb-item></el-breadcrumb><h1 class="module-title">{{ module.name }}</h1><p>{{ module.description || '暂无模块说明' }} · 负责人 {{ module.ownerName }}</p></div><div class="page-actions"><el-button @click="router.push(`/projects/${projectId}/work-items`)">返回工作项</el-button><el-button v-if="canManage" @click="moduleDialog = true">编辑模块</el-button><el-button v-if="isPm && active" type="danger" plain :disabled="!!module.nodes?.length" @click="removeModule">删除模块</el-button></div></header>
      <el-alert v-if="!active" title="项目已归档，模块和文件只读。" type="info" :closable="false" class="section-gap" />
      <el-alert v-else-if="auth.isViewer" title="当前为只读观察账号，可查看工作项和下载文件。" type="info" :closable="false" class="section-gap" />
      <div v-else-if="canRequest" class="surface request-entry section-gap"><div><strong>{{ pendingRequest ? '变更申请待审批' : '需要调整这个模块？' }}</strong><p>{{ pendingRequest ? '申请通过前，模块内容保持原样。' : '可申请修改名称、描述或负责人，由项目经理审批。' }}</p></div><el-button v-if="pendingRequest" @click="router.push(`/projects/${projectId}/settings?tab=requests&requestId=${pendingRequest.id}`)">查看我的申请</el-button><el-button v-else @click="requestDialog = true">申请变更模块</el-button></div>
      <section class="surface section-gap"><div class="section-heading"><div><h2>模块工作项</h2><p class="muted">模块用于功能划分；每个工作项有自己的负责人和计划日期。</p></div><el-button v-if="canManage" type="primary" @click="workItemDialog = true">新建工作项</el-button></div>
        <el-table :data="module.nodes || []" empty-text="本模块还没有工作项" @row-click="openNode"><el-table-column prop="name" label="工作项" min-width="180"><template #default="scope"><el-button link type="primary" @click.stop="openNode(scope.row)">{{ scope.row.name }}</el-button><small class="row-type">{{ WORK_ITEM_TYPE_LABELS[scope.row.type as WorkItemType] }}</small></template></el-table-column><el-table-column prop="ownerName" label="负责人" width="110" /><el-table-column prop="planStart" label="计划开始" width="125" /><el-table-column prop="planEnd" label="计划结束" width="125" /><el-table-column label="状态" width="105"><template #default="scope"><el-tag size="small" :type="NODE_STATUS_TYPES[scope.row.status as NodeStatus]">{{ NODE_STATUS_LABELS[scope.row.status as NodeStatus] }}</el-tag></template></el-table-column><el-table-column label="进度" width="80"><template #default="scope">{{ scope.row.progress }}%</template></el-table-column></el-table>
        <div v-if="!module.nodes?.length && canManage" class="empty module-empty"><p>建立本模块的第一项交付，再继续拆分执行任务。</p><el-button type="primary" @click="workItemDialog = true">建立首个工作项</el-button></div>
      </section>
      <section class="surface"><AttachmentPanel owner-type="MODULE" :owner-id="moduleId" :can-manage="canManage" :archived="!active" :show-category-nav="false" hint="本模块的设计、流程与开发过程文件。" /></section>
      <ModuleFormDialog v-model="moduleDialog" :project-id="projectId" :module="module" :members="members" @saved="refresh" />
      <WorkItemFormDialog v-model="workItemDialog" :project-id="projectId" :module-id="moduleId" :modules="project?.modules" :members="members" :default-owner-id="module.ownerId" :default-plan-start="project?.planStart" :business-date="businessDate" @saved="created" />
      <ChangeRequestDialog v-model="requestDialog" :module="module" :members="members" @saved="pendingRequest = $event" />
    </template>
  </div>
</template>

<style scoped>
.module-title{margin-top:15px!important}.section-gap{margin-bottom:22px}.section-heading,.request-entry{display:flex;justify-content:space-between;align-items:center;gap:18px}.section-heading h2{margin:0}.section-heading p,.request-entry p{color:var(--muted);line-height:1.7}.request-entry p{margin:8px 0 0}.row-type{display:block;color:var(--muted);margin-top:5px}.module-empty{padding:22px}.section-heading{margin-bottom:18px}
@media(max-width:600px){.section-heading,.request-entry{align-items:flex-start;flex-direction:column}}
</style>