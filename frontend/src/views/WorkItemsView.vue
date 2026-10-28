<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { NodeItem, NodeStatus } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectContext } from '../composables/useProjectContext'
import { isWorkItemManager } from '../utils/permissions'
import { NODE_STATUS_LABELS, NODE_STATUS_TYPES, WORK_ITEM_TYPE_LABELS, workItemCounts, hasWorkItemRisk } from '../utils/workItem'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'
import BugReportDialog from '../components/BugReportDialog.vue'

const route = useRoute()
const id = Number(route.params.id)
const router = useRouter()
const auth = useAuthStore()
const { project, members, loading, error, businessDate, isPm, canCreateWorkItem, refresh } = useProjectContext(id)
const workItemDialog = ref(false)
const bugDialog = ref(false)
const selectedModuleId = ref<number | null>()
const filters = reactive({ q: '', module: 'all', owner: 'all', status: 'ALL', risk: '', mine: false })
const active = computed(() => project.value?.status === 'ACTIVE')
const pmId = computed(() => members.value.find(member => member.projectRole === 'PM')?.id || 0)
const allNodes = computed(() => [...(project.value?.modules.flatMap(module => module.nodes || []) || []), ...(project.value?.projectWorkItems || [])])
const hasManagement = computed(() => isPm.value || allNodes.value.some(node => isWorkItemManager(auth.user, members.value, node)) || project.value?.modules.some(module => module.ownerId === auth.user?.id))
const counts = computed(() => ({ inProgress: allNodes.value.filter(node => node.status === 'IN_PROGRESS').length,
  overdue: allNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status) && node.flags?.overdue).length,
  blocked: allNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status) && node.flags?.blocked).length }))
const filteredNodes = computed(() => allNodes.value.filter(node => {
  if (filters.q && !`${node.name} ${node.description || ''}`.toLowerCase().includes(filters.q.trim().toLowerCase())) return false
  if (filters.module !== 'all' && (filters.module === 'project' ? !!node.moduleId : node.moduleId !== Number(filters.module))) return false
  if (filters.owner !== 'all' && node.ownerId !== Number(filters.owner)) return false
  if (filters.status === 'ALL' ? node.status === 'CANCELLED' : node.status !== filters.status) return false
  if (filters.mine && !isWorkItemManager(auth.user, members.value, node)) return false
  if (filters.risk && !['NOT_STARTED', 'IN_PROGRESS'].includes(node.status)) return false
  if (filters.risk === 'exception' && !node.flags?.overdue && !node.flags?.blocked) return false
  if (filters.risk === 'overdue' && !node.flags?.overdue) return false
  if (filters.risk === 'blocked' && !node.flags?.blocked) return false
  if (filters.risk === 'near' && !node.flags?.near) return false
  return true
}))
const groups = computed(() => {
  const showEmptyModules = !filters.q.trim() && filters.owner === 'all' && filters.status === 'ALL' && !filters.risk && !filters.mine
  const modules = (project.value?.modules || []).map(module => ({ id: module.id, name: module.name, ownerId: module.ownerId,
    ownerName: module.ownerName, totalNodes: module.nodes?.length || 0, nodes: filteredNodes.value.filter(node => node.moduleId === module.id) }))
  const general = { id: 0, name: '项目级工作项', ownerId: pmId.value, ownerName: '跨模块的测试、维护与上线保障', totalNodes: project.value?.projectWorkItems.length || 0, nodes: filteredNodes.value.filter(node => !node.moduleId) }
  return [...modules, general].filter(group => group.nodes.length || (group.id > 0 && showEmptyModules
    && (filters.module === 'all' || filters.module === String(group.id))))
})
const filtering = computed(() => !!filters.q.trim() || filters.module !== 'all' || filters.owner !== 'all' || filters.status !== 'ALL' || !!filters.risk || filters.mine)
watch(() => route.query, query => {
  filters.risk = ['exception', 'overdue', 'blocked', 'near'].includes(String(query.risk)) ? String(query.risk) : ''
  filters.owner = query.ownerId && Number(query.ownerId) > 0 ? String(query.ownerId) : 'all'
  filters.module = query.moduleId ? String(query.moduleId) : 'all'
}, { immediate: true })
function resetFilters() { Object.assign(filters, { q: '', module: 'all', owner: 'all', status: 'ALL', risk: '', mine: false }); router.replace({ path: route.path }) }
function openNode(row: NodeItem) { router.push(`/nodes/${row.id}`) }
function createWork(moduleId?: number | null) { selectedModuleId.value = moduleId; workItemDialog.value = true }
function created(node: NodeItem) { void refresh(); router.push(`/nodes/${node.id}`) }
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="refresh">重新加载</el-button></el-alert>
    <template v-if="project">
      <header class="page-head"><div><h1>工作项</h1><p>明确每项交付的负责人、日期与下一步，按功能模块分组。</p></div><div class="page-actions"><el-button @click="router.push(`/projects/${id}/modules`)">查看功能模块</el-button><el-button v-if="!auth.isViewer && active && allNodes.some(node => node.status !== 'CANCELLED')" @click="bugDialog = true">报告 Bug</el-button><el-button v-if="canCreateWorkItem" type="primary" @click="createWork()">新建工作项</el-button></div></header>
      <div class="work-summary"><span>未取消功能 {{ workItemCounts(allNodes).total }}</span><button @click="filters.status = 'IN_PROGRESS'">进行中 {{ counts.inProgress }}</button><button class="danger" @click="filters.risk = 'overdue'">延期 {{ counts.overdue }}</button><button class="purple" @click="filters.risk = 'blocked'">受阻 {{ counts.blocked }}</button></div>
      <section class="surface filters-panel">
        <el-input v-model="filters.q" clearable placeholder="搜索工作项" aria-label="搜索工作项" />
        <el-select v-model="filters.module" aria-label="所属位置"><el-option label="全部位置" value="all" /><el-option v-for="module in project.modules" :key="module.id" :label="module.name" :value="String(module.id)" /><el-option label="项目级工作项" value="project" /></el-select>
        <el-select v-model="filters.owner" aria-label="负责人"><el-option label="全部负责人" value="all" /><el-option v-for="member in members" :key="member.id" :label="member.name" :value="String(member.id)" /></el-select>
        <el-select v-model="filters.status" aria-label="工作项状态"><el-option label="全部状态（不含已取消）" value="ALL" /><el-option v-for="(label, status) in NODE_STATUS_LABELS" :key="status" :label="label" :value="status" /></el-select>
        <el-select v-model="filters.risk" clearable placeholder="全部风险" aria-label="风险"><el-option label="延期或受阻" value="exception" /><el-option label="已延期" value="overdue" /><el-option label="受阻" value="blocked" /><el-option label="临近到期" value="near" /></el-select>
        <el-checkbox v-if="hasManagement" v-model="filters.mine">我的管理范围</el-checkbox><el-button v-if="filtering" link type="primary" @click="resetFilters">清除筛选</el-button>
      </section>
      <section v-if="!groups.length" class="surface empty"><h2>{{ filtering ? '当前筛选下没有工作项' : '项目还没有工作项' }}</h2><p>{{ filtering ? '可以清除筛选查看全部工作项。' : '先建立一个小功能，明确负责人、日期与交付资料。' }}</p><el-button v-if="filtering" @click="resetFilters">清除筛选</el-button><el-button v-else-if="canCreateWorkItem" type="primary" @click="createWork()">建立首个工作项</el-button><el-button v-if="!filtering && isPm && active" @click="router.push(`/projects/${id}/modules`)">查看并新建功能模块</el-button></section>
      <section v-for="group in groups" :key="group.id" class="surface work-group">
        <div class="section-heading"><div><h2><RouterLink v-if="group.id" :to="`/projects/${id}/modules/${group.id}`">{{ group.name }}</RouterLink><template v-else>{{ group.name }}</template> <small>{{ group.nodes.length }}</small></h2><p class="muted">{{ group.id ? '模块负责人 · ' + group.ownerName : group.ownerName }}</p></div><el-button v-if="active && !auth.isViewer && (isPm || (group.id > 0 && group.ownerId === auth.user?.id))" link type="primary" @click="createWork(group.id || null)">新建工作项</el-button></div>
        <el-table :data="group.nodes" :empty-text="group.totalNodes ? '暂无未取消的工作项' : '本模块还没有工作项'" @row-click="openNode">
          <el-table-column prop="name" label="工作项" min-width="210"><template #default="scope"><el-button link type="primary" @click.stop="openNode(scope.row)">{{ scope.row.name }}</el-button><small class="row-type">{{ WORK_ITEM_TYPE_LABELS[scope.row.type as NodeItem['type']] }}</small></template></el-table-column>
          <el-table-column prop="ownerName" label="负责人" width="105" />
          <el-table-column prop="planEnd" label="计划完成" width="125" />
          <el-table-column label="状态" width="105"><template #default="scope"><el-tag size="small" :type="NODE_STATUS_TYPES[scope.row.status as NodeStatus]">{{ NODE_STATUS_LABELS[scope.row.status as NodeStatus] }}</el-tag></template></el-table-column>
          <el-table-column label="交付资料" width="130"><template #default="scope"><span v-if="scope.row.deliverableTotal">{{ scope.row.deliverableProvided || 0 }}/{{ scope.row.deliverableTotal }} 已提供</span><span v-else class="muted">未设交付要求</span></template></el-table-column>
          <el-table-column label="风险" min-width="130"><template #default="scope"><div class="flags"><el-tag v-if="hasWorkItemRisk(scope.row, 'overdue')" size="small" type="danger">已延期</el-tag><el-tag v-if="hasWorkItemRisk(scope.row, 'blocked')" size="small" type="warning">受阻</el-tag><el-tag v-if="hasWorkItemRisk(scope.row, 'near')" size="small" type="warning">临近</el-tag><span v-if="!hasWorkItemRisk(scope.row, 'overdue') && !hasWorkItemRisk(scope.row, 'blocked') && !hasWorkItemRisk(scope.row, 'near')" class="muted">—</span></div></template></el-table-column>
        </el-table>
      </section>
      <WorkItemFormDialog v-model="workItemDialog" :project-id="id" :module-id="selectedModuleId" :modules="project.modules" :can-create-project-level="isPm" :members="members" :default-plan-start="project.planStart" :business-date="businessDate" @saved="created" />
      <BugReportDialog v-model="bugDialog" :project-id="id" @saved="refresh" />
    </template>
  </div>
</template>

<style scoped>
.work-summary{display:flex;gap:20px;align-items:center;flex-wrap:wrap;margin-bottom:18px}.work-summary button{padding:0;background:none;border:0;font:inherit;cursor:pointer}.filters-panel{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:20px}.filters-panel>.el-input{width:215px}.filters-panel>.el-select{width:165px}.work-group{margin-bottom:20px}.section-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:14px}.section-heading h2{margin:0}.section-heading small{color:var(--muted);font-size:14px;margin-left:8px}.section-heading p{margin:7px 0 0;line-height:1.6}.flags{display:flex;flex-wrap:wrap;gap:5px}.row-type{display:block;margin:5px 0;color:var(--muted)}.work-group :deep(.el-table__row){cursor:pointer}.work-group :deep(.el-button){white-space:normal;text-align:left;line-height:1.6}.empty p{color:var(--muted);line-height:1.7}
@media(max-width:600px){.filters-panel>.el-input,.filters-panel>.el-select{width:100%}.section-heading{align-items:flex-start;flex-wrap:wrap}}
</style>
