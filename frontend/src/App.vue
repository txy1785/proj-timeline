<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from './api/client'
import type { NodeItem } from './api/types'
import { useAuthStore } from './stores/auth'
import { useAppStore } from './stores/app'
import { useProjectStore } from './stores/project'
import { isProjectManager } from './utils/permissions'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const context = useProjectStore()
const resolving = ref(false)
const navigationError = ref('')
let navigationVersion = 0
let ticker: ReturnType<typeof setInterval> | undefined
const showShell = computed(() => !route.meta.public)
const hasProject = computed(() => !!route.meta.projectParam || !!route.meta.nodeParam)
const project = computed(() => hasProject.value && !resolving.value && !navigationError.value ? context.project : undefined)
const projectError = computed(() => navigationError.value || (hasProject.value && !context.project ? context.error : ''))
const isPm = computed(() => isProjectManager(auth.user, context.members))
const roleLabel = computed(() => auth.isAdmin ? '管理员' : auth.isViewer ? '只读观察者' : '项目成员')
const tabs = [
  { key: 'overview', label: '概览' }, { key: 'work-items', label: '工作项' },
  { key: 'timeline', label: '时间线' }, { key: 'docs', label: '文档' },
  { key: 'reports', label: '周报' }, { key: 'settings', label: '设置' }
]
function activeTab(key: string) {
  if (key === 'work-items') return !!route.meta.nodeParam || route.path.includes('/modules') || route.path.endsWith('/work-items')
  return route.path.endsWith('/' + key)
}
async function resolveProject() {
  const version = ++navigationVersion
  navigationError.value = ''
  if (!auth.authenticated || !hasProject.value) { resolving.value = false; return }
  resolving.value = true
  try {
    let id: number
    if (route.meta.nodeParam) {
      const node = await api<NodeItem>('GET', '/nodes/' + Number(route.params[String(route.meta.nodeParam)]))
      id = node.projectId
    } else id = Number(route.params[String(route.meta.projectParam)])
    if (version !== navigationVersion) return
    await context.load(id, true)
  } catch (e) {
    if (version === navigationVersion) navigationError.value = (e as Error).message
  } finally { if (version === navigationVersion) resolving.value = false }
}
watch(() => route.path, () => { void resolveProject(); if (auth.authenticated) void app.refresh() }, { immediate: true })
watch(() => auth.user?.globalRole, () => {
  if ((route.meta.admin && !auth.isAdmin) || (route.meta.workspace && auth.isViewer)) void router.replace('/projects')
})
onMounted(() => {
  if (auth.authenticated) void auth.refresh().catch(() => undefined)
  ticker = setInterval(() => { if (auth.authenticated) void app.refresh() }, 60000)
})
onBeforeUnmount(() => { if (ticker) clearInterval(ticker) })
function logout() { auth.logout(); context.reset(); app.reset(); void router.push('/login') }
async function retry() { context.reset(); await resolveProject() }
</script>

<template>
  <RouterView v-if="!showShell" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/"><span class="brand-mark">时</span><span class="nav-label">项目时间线</span></RouterLink>
      <nav aria-label="工作空间">
        <RouterLink v-if="!auth.isViewer" to="/work"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 3h14v18H5zM8 7h8M8 11h8M8 15h5" /></svg><span class="nav-label">我的工作</span></RouterLink>
        <RouterLink to="/projects"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h7l2-3h9v16H3zM3 9h18" /></svg><span class="nav-label">项目</span></RouterLink>
        <RouterLink to="/notifications"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5zM10 21h4" /></svg><span class="nav-label">通知</span><span v-if="app.context?.unreadCount" class="unread-badge" aria-label="未读通知">{{app.context.unreadCount}}</span></RouterLink>
      </nav>
      <nav class="sidebar-secondary" aria-label="更多工具">
        <RouterLink to="/workload"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 21V11h4v10M10 21V3h4v18M16 21v-7h4v7" /></svg><span class="nav-label">人员负载</span></RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/users"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 21H3v-3c0-4 13-4 13 0zM13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M18 5h4M20 3v4" /></svg><span class="nav-label">账号管理</span></RouterLink>
      </nav>
      <div class="sidebar-user"><span class="avatar" aria-hidden="true">{{auth.user?.name.slice(0,1)}}</span><div class="nav-label"><strong>{{auth.user?.name}}</strong><small>{{roleLabel}}</small></div><el-button text aria-label="退出登录" @click="logout">退出</el-button></div>
    </aside>
    <main class="content">
      <div class="app-topbar"><span><RouterLink to="/projects">{{hasProject?'项目':'工作空间'}}</RouterLink><template v-if="project"> / <RouterLink :to="`/projects/${project.id}/overview`">{{project.name}}</RouterLink></template></span><span class="muted">{{app.businessDate || '业务日期加载中'}} <span v-if="project&&isPm" class="role-chip">项目经理</span></span></div>
      <el-alert v-if="app.error" :title="`业务日期与通知读取失败：${app.error}`" type="warning" :closable="false" show-icon><el-button link @click="app.refresh">重新读取</el-button></el-alert>
      <nav v-if="project" class="project-tabs" aria-label="当前项目">
        <RouterLink v-for="tab in tabs" :key="tab.key" :to="`/projects/${project.id}/${tab.key}`" :class="{selected:activeTab(tab.key)}" :aria-current="activeTab(tab.key)?'page':undefined">{{tab.label}}</RouterLink>
      </nav>
      <el-alert v-if="project&&context.error" :title="`项目刷新失败：${context.error}`" type="warning" :closable="false"><el-button link @click="context.load(project.id,true)">重试刷新</el-button></el-alert>
      <el-alert v-if="project?.status==='ARCHIVED'" class="archive-banner" title="项目已归档，当前为只读历史" type="info" :closable="false"><RouterLink v-if="isPm" :to="`/projects/${project.id}/settings`">前往设置恢复项目</RouterLink></el-alert>
      <section v-if="hasProject&&projectError" class="surface empty" role="alert"><h2>暂时无法打开这个项目或工作项</h2><p>{{projectError}}</p><el-button @click="retry">重试</el-button><RouterLink to="/projects"><el-button type="primary">返回项目列表</el-button></RouterLink></section>
      <el-skeleton v-else-if="hasProject&&(resolving||!project)" :rows="7" animated class="surface" />
      <RouterView v-else :key="route.path" />
    </main>
  </div>
</template>
