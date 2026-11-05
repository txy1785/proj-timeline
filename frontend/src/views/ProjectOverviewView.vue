<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { NodeItem } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectContext } from '../composables/useProjectContext'
import { addDays, compareOptionalDates, shortDate, weekStart } from '../utils/date'
import { canManageNode } from '../utils/permissions'
import { hasWorkItemRisk, workItemCounts } from '../utils/workItem'
import { deliveredPercent, scheduleProgress } from '../utils/progress'
import ProgressSummary from '../components/ProgressSummary.vue'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'
import ModuleFormDialog from '../components/ModuleFormDialog.vue'
import BugReportDialog from '../components/BugReportDialog.vue'

type NodeTag = { label: string; type: 'danger' | 'warning' | 'primary' | 'info' }

const ACTION_LIMIT = 5
const id = Number(useRoute().params.id)
const router = useRouter()
const auth = useAuthStore()
const { project, members, loading, error, businessDate, isPm, canCreateWorkItem, refresh } = useProjectContext(id)
const workItemDialog = ref(false)
const moduleDialog = ref(false)
const bugDialog = ref(false)
const descriptionExpanded = ref(false)
const active = computed(() => project.value?.status === 'ACTIVE')
const pm = computed(() => members.value.find(member => member.projectRole === 'PM'))
const stats = computed(() => project.value?.stats)
const longDescription = computed(() => { const text = project.value?.description || ''; return text.length > 90 || text.includes('\n') })
const allNodes = computed(() => [...(project.value?.modules.flatMap(module => module.nodes || []) || []), ...(project.value?.projectWorkItems || [])])
const activeNodes = computed(() => allNodes.value.filter(node => node.status !== 'CANCELLED'))
const progress = computed(() => scheduleProgress(allNodes.value, businessDate.value, project.value))
const isRisk = (node: NodeItem) => !!(node.flags?.overdue || node.flags?.blocked)
function moduleBehind(nodes: NodeItem[] = []) { return nodes.filter(node => hasWorkItemRisk(node, 'overdue')).length }
const openNodes = computed(() => activeNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status)))
const risks = computed(() => openNodes.value.filter(isRisk))
function actionRank(node: NodeItem) {
  if (isRisk(node)) return 0
  if (node.status === 'IN_PROGRESS') return 1
  return node.planEnd ? 3 : 2
}
const actionNodes = computed(() => [...openNodes.value].sort((a, b) => actionRank(a) - actionRank(b) || compareOptionalDates(a.planEnd, b.planEnd) || a.id - b.id))
const needsAction = computed(() => actionNodes.value.slice(0, ACTION_LIMIT))
function byActualEndDesc(a: NodeItem, b: NodeItem) {
  if (!a.actualEnd || !b.actualEnd) return compareOptionalDates(a.actualEnd, b.actualEnd) || b.id - a.id
  return b.actualEnd.localeCompare(a.actualEnd) || b.id - a.id
}
const recentDone = computed(() => activeNodes.value.filter(node => node.status === 'DONE').sort(byActualEndDesc).slice(0, ACTION_LIMIT))
const nextTarget = computed(() => businessDate.value ? [...(project.value?.stageTargets || [])].filter(target => target.targetDate >= businessDate.value).sort((a, b) => a.targetDate.localeCompare(b.targetDate) || a.id - b.id)[0] : undefined)
const weekDue = computed(() => {
  const start = weekStart(businessDate.value)
  const end = addDays(start, 6)
  return start ? openNodes.value.filter(node => !!node.planEnd && node.planEnd >= start && node.planEnd <= end).sort((a, b) => compareOptionalDates(a.planEnd, b.planEnd)) : []
})
function nodeTags(node: NodeItem): NodeTag[] {
  const tags: NodeTag[] = []
  if (node.flags?.overdue) tags.push({ label: '已延期', type: 'danger' })
  if (node.flags?.blocked) tags.push({ label: '受阻', type: 'warning' })
  if (tags.length) return tags
  if (node.flags?.near) tags.push({ label: '临近到期', type: 'warning' })
  else if (node.status === 'IN_PROGRESS') tags.push({ label: '进行中', type: 'primary' })
  else tags.push({ label: node.planEnd ? '未开始' : '待排期', type: 'info' })
  return tags
}
function riskReason(node: NodeItem) {
  if (node.flags?.overdue) return `延期：${node.overdueReason || '负责人尚未补充原因'}`
  if (node.flags?.blocked) return `受阻：${node.blockedReason || '负责人尚未补充原因'}`
  return ''
}
function openNode(node: NodeItem) { router.push(`/nodes/${node.id}`) }
function created(node: NodeItem, options?: { continue?: boolean }) { void refresh(); if (!options?.continue) router.push(`/nodes/${node.id}`) }
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="refresh">重新加载项目</el-button></el-alert>
    <template v-if="project">
      <header class="page-head overview-head">
        <div class="head-main">
          <div class="heading-line"><h1>{{ project.name }}</h1><el-tag :type="active ? 'primary' : 'info'">{{ active ? '进行中' : '已归档' }}</el-tag></div>
          <p v-if="project.description" class="project-purpose" :class="{ clamped: !descriptionExpanded }">{{ project.description }}</p>
          <el-button v-if="project.description && longDescription" link type="primary" size="small" @click="descriptionExpanded = !descriptionExpanded">{{ descriptionExpanded ? '收起' : '展开说明' }}</el-button>
          <p v-else-if="!project.description" class="project-purpose muted">尚未填写项目说明 <el-button v-if="isPm && active" link type="primary" @click="router.push(`/projects/${id}/settings`)">补充说明</el-button></p>
        </div>
        <div class="page-actions">
          <el-button v-if="canCreateWorkItem" type="primary" @click="workItemDialog = true">新建工作项</el-button>
          <el-button v-if="!auth.isViewer && active && activeNodes.length" @click="bugDialog = true">报告 Bug</el-button>
        </div>
      </header>
      <el-alert v-if="!active" title="项目已归档，以下为保留的交付记录。业务内容只读。" type="info" :closable="false" class="section-gap" />

      <section v-if="!allNodes.length" class="surface first-work section-gap">
        <template v-if="canCreateWorkItem"><span class="step-label">项目已建立 · 成员和目标可以稍后完善</span><h2>下一步：建立第一项交付</h2><p>每个工作项代表一个可交付的小功能，写清负责人、日期和交付资料即可开始。</p><div class="page-actions"><el-button type="primary" @click="workItemDialog = true">建立首个工作项</el-button><el-button v-if="isPm" @click="router.push(`/projects/${id}/settings?tab=members`)">添加成员</el-button></div></template>
        <template v-else><h2>项目还没有工作项</h2><p>{{ active ? `项目经理尚未安排交付，可联系${pm?.name || '项目经理'}了解下一步。` : '该项目没有保留的工作项。' }}</p></template>
      </section>

      <section v-else class="surface current-work section-gap" aria-label="当前工作">
        <div class="section-heading">
          <h2>{{ active ? '需要处理' : '未完成记录' }}</h2>
          <div class="heading-links">
            <el-button v-if="risks.length" link type="danger" @click="router.push(`/projects/${id}/work-items?risk=exception`)">{{ risks.length }} 项延期或受阻</el-button>
            <el-button v-if="actionNodes.length > ACTION_LIMIT" link type="primary" @click="router.push(`/projects/${id}/work-items`)">全部 {{ actionNodes.length }} 项 →</el-button>
          </div>
        </div>
        <p v-if="!needsAction.length" class="muted brief-empty">暂无未完成工作项。</p>
        <article v-for="node in needsAction" :key="node.id" class="work-row" :class="{ risk: node.flags?.overdue, blocked: node.flags?.blocked && !node.flags?.overdue }">
          <div class="work-main">
            <div class="work-line"><el-tag v-for="tag in nodeTags(node)" :key="tag.label" :type="tag.type" size="small">{{ tag.label }}</el-tag><button class="work-title" @click="openNode(node)">{{ node.name }}</button></div>
            <p class="work-meta">{{ node.ownerName || '未分配' }} · {{ node.planEnd ? `计划完成 ${shortDate(node.planEnd)}` : '待排期' }}<span v-if="riskReason(node)" class="work-reason"> · {{ riskReason(node) }}</span></p>
          </div>
          <el-button size="small" :type="isRisk(node) && canManageNode(auth.user, members, node) ? 'primary' : 'default'" @click="openNode(node)">{{ canManageNode(auth.user, members, node) ? '处理' : '查看' }}</el-button>
        </article>
        <template v-if="recentDone.length">
          <h3 class="sub-heading">最近交付</h3>
          <button v-for="node in recentDone" :key="node.id" class="done-row" @click="openNode(node)"><span class="done-name">{{ node.name }}</span><span class="muted">{{ node.ownerName || '未分配' }} · {{ node.actualEnd ? `交付 ${shortDate(node.actualEnd)}` : '交付日期未记录' }}</span></button>
        </template>
      </section>

      <ProgressSummary v-if="allNodes.length" class="section-gap" :progress="progress" :project-id="id" :today="businessDate" />

      <section class="surface project-facts section-gap" aria-label="项目交付信息">
        <div><small>项目经理</small><strong>{{ pm?.name || '未读取到负责人' }}</strong></div>
        <div><small>项目计划</small><strong>{{ project.planStart }} — {{ project.planEnd || '完成日期未定' }}</strong></div>
        <div v-if="stats?.unassigned != null"><small>未分配负责人</small><strong>{{ stats.unassigned }}</strong></div>
        <div v-if="stats?.emptyModules != null"><small>未拆分模块</small><strong>{{ stats.emptyModules }}</strong></div>
      </section>

      <div v-if="allNodes.length" class="plan-grid section-gap">
        <section class="surface target-card"><h2>下一阶段目标</h2><template v-if="nextTarget"><h3>{{ nextTarget.name }}</h3><strong class="target-date">{{ nextTarget.targetDate }}</strong><p v-if="nextTarget.description" class="muted">{{ nextTarget.description }}</p><p v-if="nextTarget.progress != null">{{ nextTarget.metricName }}：{{ nextTarget.metricCurrent ?? 0 }}/{{ nextTarget.metricTarget }} {{ nextTarget.metricUnit }} · 达成 {{ nextTarget.progress }}%</p></template><p v-else class="muted">{{ businessDate ? '暂无后续阶段性目标' : '业务日期暂不可用，目标请在交付排期中查看。' }}</p><el-button link type="primary" @click="router.push(`/projects/${id}/timeline?targets=open`)">{{ isPm && active ? '编辑目标' : '查看目标' }} →</el-button></section>
        <section class="surface"><h2>本周计划交付</h2><p v-if="!weekDue.length" class="muted">本周暂无未完成的计划交付。</p><button v-for="node in weekDue" :key="node.id" class="delivery-row" @click="openNode(node)"><span>{{ node.name }}<small>{{ node.ownerName || '未分配' }}</small></span><el-tag v-if="node.flags.overdue" type="danger" size="small">已延期</el-tag><span v-else>{{ shortDate(node.planEnd) }}</span></button></section>
      </div>

      <section class="surface module-overview section-gap" aria-label="功能模块">
        <div class="section-heading"><h2>功能模块</h2><div class="page-actions"><el-button v-if="isPm && active" @click="moduleDialog = true">新建模块</el-button><RouterLink :to="`/projects/${id}/modules`"><el-button>查看全部模块</el-button></RouterLink></div></div>
        <div v-if="project.modules.length" class="module-preview-grid"><RouterLink v-for="module in project.modules" :key="module.id" class="module-preview" :to="`/projects/${id}/modules/${module.id}`"><strong>{{ module.name }}</strong><span>负责人 {{ module.ownerName }} · <template v-if="module.nodes?.length">{{ workItemCounts(module.nodes).done }}/{{ workItemCounts(module.nodes).total }} 已交付</template><template v-else>尚未拆分</template><b v-if="moduleBehind(module.nodes)" class="danger"> · {{ moduleBehind(module.nodes) }} 项延期</b></span><i class="module-bar" :class="{ behind: moduleBehind(module.nodes) }" aria-hidden="true"><i :style="{ width: deliveredPercent(workItemCounts(module.nodes || []).done, workItemCounts(module.nodes || []).total) + '%' }" /></i><small>进入模块 →</small></RouterLink></div>
        <p v-else class="muted">尚未划分功能模块。跨模块工作仍可在“工作项”中安排。</p>
      </section>
      <div v-if="allNodes.length" class="overview-footer"><el-button link type="primary" @click="router.push(`/projects/${id}/work-items`)">查看工作项 →</el-button></div>
      <WorkItemFormDialog v-model="workItemDialog" :project-id="id" :members="members" :modules="project.modules" :can-create-project-level="isPm" :default-plan-start="project.planStart" :business-date="businessDate" @saved="created" />
      <ModuleFormDialog v-model="moduleDialog" :project-id="id" :members="members" :default-owner-id="pm?.id" @saved="refresh" />
      <BugReportDialog v-model="bugDialog" :project-id="id" @saved="refresh" />
    </template>
  </div>
</template>

<style scoped>
.overview-head{align-items:flex-start;gap:16px;margin-bottom:16px}.head-main{min-width:0;flex:1}.heading-line{display:flex;align-items:center;gap:14px}.heading-line h1{margin-bottom:0}
.project-purpose{max-width:760px;margin:8px 0 0;white-space:pre-line;line-height:1.6}.project-purpose.clamped{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.section-gap{margin-bottom:18px}.section-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.section-heading h2{margin:0 0 10px}.heading-links{display:flex;gap:4px;flex-wrap:wrap}
.current-work{padding-top:18px;padding-bottom:14px}.brief-empty{margin:4px 0 8px}
.work-row{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:10px 12px;margin:6px 0;border:1px solid var(--line);border-radius:8px}.work-row.risk{background:#fff6f6;border-color:#f1d7db}.work-row.blocked{background:#f7f3fd;border-color:#e5ddf2}
.work-main{min-width:0;flex:1}.work-line{display:flex;align-items:center;gap:6px;min-width:0}.work-title{padding:0;border:0;background:none;font:inherit;font-weight:700;font-size:15px;color:var(--navy);text-align:left;cursor:pointer;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.work-meta{margin:4px 0 0;font-size:13px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.work-reason{color:var(--navy)}
.sub-heading{margin:14px 0 4px;font-size:14px;color:var(--muted)}.done-row,.delivery-row{display:flex;align-items:center;justify-content:space-between;gap:14px;width:100%;padding:9px 0;background:none;border:0;border-top:1px solid var(--line);font:inherit;text-align:left;color:var(--navy);cursor:pointer}.done-row{font-size:14px}.done-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.done-row .muted{flex-shrink:0;font-size:13px}
.project-facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:18px}.project-facts>div{display:flex;flex-direction:column;gap:8px}.project-facts small{color:var(--muted)}.project-facts strong{font-size:16px}
.plan-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;align-items:start}.delivery-row{padding:13px 0}.delivery-row small{display:block;margin-top:6px;color:var(--muted)}.target-card h3{font-size:20px;margin:16px 0 10px}.target-date{font-size:18px}.target-card p{line-height:1.7}
.first-work{padding:34px}.first-work p{line-height:1.8}.step-label{font-size:13px;color:var(--muted)}.overview-footer{text-align:center;padding:20px}
.module-overview .section-heading h2{margin-bottom:0}.module-preview-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(235px,1fr));gap:12px;margin-top:16px}.module-preview{display:flex;flex-direction:column;gap:8px;padding:16px;border:1px solid var(--line);border-radius:8px;color:var(--navy)}.module-preview:hover{border-color:var(--blue)}.module-preview span{color:var(--muted);font-size:13px}.module-preview small{color:var(--blue)}.module-bar{display:block;height:6px;border-radius:3px;background:#e7ecf3;overflow:hidden}.module-bar>i{display:block;height:100%;background:var(--success)}.module-bar.behind>i{background:var(--danger)}
@media(max-width:1100px){.plan-grid{grid-template-columns:1fr}}
@media(max-width:600px){.overview-head{flex-direction:column;gap:10px;margin-bottom:12px}.overview-head .page-actions{width:100%}.heading-line{flex-wrap:wrap;gap:8px}.section-gap{margin-bottom:14px}.work-row{padding:8px 10px;gap:8px}.work-title{font-size:14px}.work-meta{font-size:12px}.project-facts{grid-template-columns:1fr 1fr;gap:14px 12px}.first-work{padding:22px}.module-overview .section-heading{flex-wrap:wrap}}
</style>
