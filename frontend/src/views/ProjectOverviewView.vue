<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { NodeItem } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useProjectContext } from '../composables/useProjectContext'
import { addDays, shortDate, weekStart } from '../utils/date'
import { canManageNode } from '../utils/permissions'
import { workItemCounts } from '../utils/workItem'
import WorkItemFormDialog from '../components/WorkItemFormDialog.vue'
import ModuleFormDialog from '../components/ModuleFormDialog.vue'
import BugReportDialog from '../components/BugReportDialog.vue'

const id = Number(useRoute().params.id)
const router = useRouter()
const auth = useAuthStore()
const { project, members, loading, error, businessDate, isPm, canCreateWorkItem, refresh } = useProjectContext(id)
const workItemDialog = ref(false)
const moduleDialog = ref(false)
const bugDialog = ref(false)
const active = computed(() => project.value?.status === 'ACTIVE')
const pm = computed(() => members.value.find(member => member.projectRole === 'PM'))
const allNodes = computed(() => [...(project.value?.modules.flatMap(module => module.nodes || []) || []), ...(project.value?.projectWorkItems || [])])
const activeNodes = computed(() => allNodes.value.filter(node => node.status !== 'CANCELLED'))
const done = computed(() => workItemCounts(allNodes.value).done)
const risks = computed(() => activeNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status) && (node.flags?.overdue || node.flags?.blocked)).sort((a, b) => a.planEnd.localeCompare(b.planEnd) || a.id - b.id))
const near = computed(() => activeNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status) && node.flags?.near).sort((a, b) => a.planEnd.localeCompare(b.planEnd)))
const nextTarget = computed(() => businessDate.value ? [...(project.value?.stageTargets || [])].filter(target => target.targetDate >= businessDate.value).sort((a, b) => a.targetDate.localeCompare(b.targetDate) || a.id - b.id)[0] : undefined)
const weekDue = computed(() => {
  const start = weekStart(businessDate.value)
  const end = addDays(start, 6)
  return start ? activeNodes.value.filter(node => ['NOT_STARTED', 'IN_PROGRESS'].includes(node.status) && node.planEnd >= start && node.planEnd <= end).sort((a, b) => a.planEnd.localeCompare(b.planEnd)) : []
})
function openNode(node: NodeItem) { router.push(`/nodes/${node.id}`) }
function created(node: NodeItem) { void refresh(); router.push(`/nodes/${node.id}`) }
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="refresh">重新加载项目</el-button></el-alert>
    <template v-if="project">
      <header class="page-head">
        <div><div class="heading-line"><h1>{{ project.name }}</h1><el-tag :type="active ? 'primary' : 'info'">{{ active ? '进行中' : '已归档' }}</el-tag></div><p class="project-purpose">{{ project.description || '尚未填写项目说明' }} <el-button v-if="isPm && active && !project.description" link type="primary" @click="router.push(`/projects/${id}/settings`)">补充说明</el-button></p></div>
        <div class="page-actions"><el-button v-if="canCreateWorkItem" type="primary" @click="workItemDialog = true">新建工作项</el-button><el-button v-if="!auth.isViewer && active && allNodes.some(node => node.status !== 'CANCELLED')" @click="bugDialog = true">报告 Bug</el-button></div>
      </header>
      <el-alert v-if="!active" title="项目已归档，以下为保留的交付记录。业务内容只读。" type="info" :closable="false" class="section-gap" />
      <section class="surface project-facts section-gap" aria-label="项目交付信息">
        <div><small>项目经理</small><strong>{{ pm?.name || '未读取到负责人' }}</strong></div>
        <div><small>项目计划</small><strong>{{ project.planStart }} — {{ project.planEnd || '完成日期未定' }}</strong></div>
        <div class="fact-progress"><small>功能交付</small><strong>{{ done }}/{{ activeNodes.length }} 已交付</strong></div>
        <div><small>当前交付风险</small><strong>{{ risks.length }} 项延期或受阻</strong></div>
      </section>
      <section class="surface module-overview section-gap" aria-label="功能模块">
        <div class="section-heading"><div><h2>功能模块</h2><p class="muted">项目按模块划分功能，每个模块下安排小功能工作项。</p></div><div class="page-actions"><el-button v-if="isPm && active" @click="moduleDialog = true">新建模块</el-button><RouterLink :to="`/projects/${id}/modules`"><el-button>查看全部模块</el-button></RouterLink></div></div>
        <div v-if="project.modules.length" class="module-preview-grid"><RouterLink v-for="module in project.modules" :key="module.id" class="module-preview" :to="`/projects/${id}/modules/${module.id}`"><strong>{{ module.name }}</strong><span>负责人 {{ module.ownerName }} · {{ workItemCounts(module.nodes || []).done }}/{{ workItemCounts(module.nodes || []).total }} 已交付</span><small>进入模块 →</small></RouterLink></div>
        <p v-else class="muted">尚未划分功能模块。跨模块工作仍可在“工作项”中安排。</p>
      </section>
      <section v-if="!allNodes.length" class="surface first-work section-gap">
        <template v-if="canCreateWorkItem"><span class="step-label">项目已建立 · 成员和目标可以稍后完善</span><h2>下一步：建立第一项交付</h2><p>每个工作项代表一个可交付的小功能，写清负责人、日期和交付资料即可开始。</p><div class="page-actions"><el-button type="primary" @click="workItemDialog = true">建立首个工作项</el-button><el-button v-if="isPm" @click="router.push(`/projects/${id}/settings?tab=members`)">添加成员</el-button></div><p class="muted">功能工作选择功能模块；跨模块测试、维护和上线保障选择项目级工作项。</p></template>
        <template v-else><h2>项目还没有工作项</h2><p>{{ active ? `项目经理尚未安排交付，可联系${pm?.name || '项目经理'}了解下一步。` : '该项目没有保留的工作项。' }}</p></template>
      </section>
      <div v-else class="overview-grid">
        <section class="surface">
          <div class="section-heading"><h2>{{ active ? '需要处理' : '交付风险记录' }}</h2><el-button v-if="risks.length" link type="primary" @click="router.push(`/projects/${id}/work-items?risk=exception`)">查看 {{ risks.length }} 个工作项</el-button></div>
          <div v-if="!risks.length" class="empty"><strong>当前没有延期或受阻工作项</strong><p>可继续查看近期交付计划与待处理 Bug。</p></div>
          <article v-for="node in risks" :key="node.id" class="risk-card" :class="{ blocked: node.flags.blocked && !node.flags.overdue }">
            <div class="risk-main"><div class="risk-tags"><el-tag v-if="node.flags.overdue" type="danger" size="small">已延期</el-tag><el-tag v-if="node.flags.blocked" type="warning" size="small">受阻</el-tag></div><button class="work-title" @click="openNode(node)">{{ node.name }}</button><p class="muted">负责人 {{ node.ownerName }} · 计划完成 {{ node.planEnd }}</p><p v-if="node.flags.overdue">延期说明：{{ node.overdueReason || '负责人尚未补充原因' }}</p><p v-if="node.flags.blocked">受阻原因：{{ node.blockedReason || '负责人尚未补充原因' }}</p></div>
            <el-button :type="canManageNode(auth.user, members, node) ? 'primary' : 'default'" @click="openNode(node)">{{ canManageNode(auth.user, members, node) ? '查看并处理' : '查看详情' }}</el-button>
          </article>
          <button v-for="node in near" :key="node.id" class="near-row" @click="openNode(node)"><span>临近到期：<strong>{{ node.name }}</strong></span><span>{{ node.ownerName }} · {{ shortDate(node.planEnd) }} →</span></button>
        </section>
        <aside class="overview-side">
          <section class="surface target-card"><h2>下一阶段目标</h2><template v-if="nextTarget"><h3>{{ nextTarget.name }}</h3><strong class="target-date">{{ nextTarget.targetDate }}</strong><p v-if="nextTarget.description" class="muted">{{ nextTarget.description }}</p><p v-if="nextTarget.progress != null">{{ nextTarget.metricName }}：{{ nextTarget.metricCurrent ?? 0 }}/{{ nextTarget.metricTarget }} {{ nextTarget.metricUnit }} · 达成 {{ nextTarget.progress }}%</p></template><p v-else class="muted">{{ businessDate ? '暂无后续阶段性目标' : '业务日期暂不可用，目标请在设置中查看。' }}</p><el-button link type="primary" @click="router.push(`/projects/${id}/settings?tab=targets`)">{{ isPm && active ? '查看与维护目标' : '查看全部目标' }} →</el-button></section>
          <section class="surface"><h2>本周计划交付</h2><div v-if="!weekDue.length" class="empty compact">本周暂无未完成的计划交付。</div><button v-for="node in weekDue" :key="node.id" class="delivery-row" @click="openNode(node)"><span>{{ node.name }}<small>{{ node.ownerName }}</small></span><el-tag v-if="node.flags.overdue" type="danger" size="small">已延期</el-tag><span v-else>{{ shortDate(node.planEnd) }}</span></button></section>
        </aside>
      </div>
      <div v-if="allNodes.length" class="overview-footer"><el-button link type="primary" @click="router.push(`/projects/${id}/work-items`)">查看工作项 →</el-button></div>
      <WorkItemFormDialog v-model="workItemDialog" :project-id="id" :members="members" :modules="project.modules" :can-create-project-level="isPm" :default-plan-start="project.planStart" :business-date="businessDate" @saved="created" />
      <ModuleFormDialog v-model="moduleDialog" :project-id="id" :members="members" :default-owner-id="pm?.id" @saved="refresh" />
      <BugReportDialog v-model="bugDialog" :project-id="id" @saved="refresh" />
    </template>
  </div>
</template>

<style scoped>
.heading-line{display:flex;align-items:center;gap:14px}.heading-line h1{margin-bottom:0}.project-purpose{max-width:760px;white-space:pre-line;line-height:1.7}.section-gap{margin-bottom:22px}.project-facts{display:grid;grid-template-columns:1fr 1.5fr 1.25fr 1fr;gap:22px}.project-facts>div{display:flex;flex-direction:column;gap:10px}.project-facts small{color:var(--muted)}.project-facts strong{font-size:16px}.progress-inline{display:flex;align-items:center;gap:14px}.progress-inline .el-progress{flex:1}.overview-grid{display:grid;grid-template-columns:minmax(0,1.65fr) minmax(270px,1fr);gap:22px;align-items:start}.section-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.section-heading h2{margin:0 0 16px}.overview-side{display:grid;gap:22px}.risk-card{display:flex;justify-content:space-between;align-items:center;gap:18px;padding:18px;margin:12px 0;background:#fff6f6;border:1px solid #f1d7db;border-radius:9px}.risk-card.blocked{background:#f7f3fd;border-color:#e5ddf2}.risk-card p{margin:8px 0 0;line-height:1.6}.risk-main{min-width:0;overflow-wrap:anywhere}.risk-tags{display:flex;gap:6px;margin-bottom:10px}.work-title{padding:0;border:0;background:none;font:inherit;font-weight:700;font-size:17px;color:var(--navy);text-align:left;cursor:pointer}.near-row,.delivery-row{display:flex;align-items:center;justify-content:space-between;gap:14px;width:100%;padding:15px 0;background:none;border:0;border-top:1px solid var(--line);font:inherit;text-align:left;color:var(--navy);cursor:pointer}.near-row{margin-top:16px;color:#8f6519;font-size:14px;flex-wrap:wrap}.delivery-row small{display:block;margin-top:6px;color:var(--muted)}.target-card h3{font-size:22px;margin:20px 0 12px}.target-date{font-size:20px}.target-card p{line-height:1.7}.first-work{padding:34px}.first-work p{line-height:1.8}.step-label{font-size:13px;color:var(--muted)}.overview-footer{text-align:center;padding:20px}.compact{padding:24px 0}.empty p{font-size:14px}
.module-overview .section-heading h2{margin-bottom:5px}.module-overview .section-heading p{margin:0}.module-preview-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(235px,1fr));gap:12px;margin-top:18px}.module-preview{display:flex;flex-direction:column;gap:8px;padding:16px;border:1px solid var(--line);border-radius:8px;color:var(--navy)}.module-preview:hover{border-color:var(--blue)}.module-preview span{color:var(--muted);font-size:13px}.module-preview small{color:var(--blue)}
@media(max-width:1100px){.overview-grid{grid-template-columns:1fr}.overview-side{grid-template-columns:1fr 1fr}.project-facts{grid-template-columns:1fr 1fr}}
@media(max-width:600px){.overview-side{grid-template-columns:1fr}.project-facts{grid-template-columns:1fr 1fr;gap:20px 12px}.risk-card{align-items:flex-start;flex-direction:column}.first-work{padding:22px}.heading-line,.module-overview .section-heading{flex-wrap:wrap}}
</style>
