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
const drawerOpen = ref(false)
let navigationVersion = 0
let ticker: ReturnType<typeof setInterval> | undefined
const showShell = computed(() => !route.meta.public)
const hasProject = computed(() => !!route.meta.projectParam || !!route.meta.nodeParam)
const project = computed(() => hasProject.value && !resolving.value && !navigationError.value ? context.project : undefined)
const projectError = computed(() => navigationError.value || (hasProject.value && !context.project ? context.error : ''))
const isPm = computed(() => isProjectManager(auth.user, context.members))
const roleLabel = computed(() => auth.isAdmin ? '管理员' : auth.isViewer ? '只读观察者' : '项目成员')
const primaryTabs = [
  { key: 'overview', label: '概览' }, { key: 'work-items', label: '工作' },
  { key: 'timeline', label: '计划' }, { key: 'docs', label: '资料' }
]
const moreTabs = [{ key: 'modules', label: '模块' }, { key: 'reports', label: '周报' }, { key: 'settings', label: '设置' }]
const moreActive = computed(() => activeTab('reports') || activeTab('settings'))
function activeTab(key: string) {
  if (key === 'modules') return route.path.includes('/modules')
  if (key === 'work-items') return !!route.meta.nodeParam || route.path.endsWith('/work-items') || route.path.includes('/modules')
  return route.path.endsWith('/' + key)
}
function openMoreTab(key: string) {
  if (project.value) void router.push(`/projects/${project.value.id}/${key}`)
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
watch(() => route.fullPath, () => { drawerOpen.value = false })
watch(() => auth.user?.globalRole, () => {
  if ((route.meta.admin && !auth.isAdmin) || (route.meta.workspace && auth.isViewer)) void router.replace('/projects')
})
onMounted(() => {
  if (auth.authenticated) void auth.refresh().catch(() => undefined)
  ticker = setInterval(() => { if (auth.authenticated) void app.refresh() }, 60000)
})
onBeforeUnmount(() => { if (ticker) clearInterval(ticker) })
function logout() { drawerOpen.value = false; auth.logout(); context.reset(); app.reset(); void router.push('/login') }
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
    <el-drawer v-model="drawerOpen" direction="ltr" size="280px" title="菜单" class="mobile-drawer">
      <nav class="drawer-nav" aria-label="工作空间">
        <RouterLink v-if="!auth.isViewer" to="/work" @click="drawerOpen=false">我的工作</RouterLink>
        <RouterLink to="/projects" @click="drawerOpen=false">项目</RouterLink>
        <RouterLink to="/notifications" @click="drawerOpen=false">通知<span v-if="app.context?.unreadCount" class="unread-badge" aria-label="未读通知">{{app.context.unreadCount}}</span></RouterLink>
        <RouterLink to="/workload" @click="drawerOpen=false">人员负载</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/users" @click="drawerOpen=false">账号管理</RouterLink>
      </nav>
      <div class="drawer-user"><div><strong>{{auth.user?.name}}</strong><small>{{roleLabel}}</small></div><el-button class="drawer-logout" aria-label="退出登录" @click="logout">退出</el-button></div>
    </el-drawer>
    <main class="content">
      <div class="app-topbar"><el-button class="menu-button" aria-label="打开菜单" :aria-expanded="drawerOpen" @click="drawerOpen=true">菜单</el-button><span class="topbar-crumbs"><RouterLink to="/projects">{{hasProject?'项目':'工作空间'}}</RouterLink><template v-if="project"> / <RouterLink :to="`/projects/${project.id}/overview`">{{project.name}}</RouterLink></template></span><span class="muted">{{app.businessDate || '业务日期加载中'}} <span v-if="project&&isPm" class="role-chip">项目经理</span></span></div>
      <el-alert v-if="app.error" :title="`业务日期与通知读取失败：${app.error}`" type="warning" :closable="false" show-icon><el-button link @click="app.refresh">重新读取</el-button></el-alert>
      <nav v-if="project" class="project-tabs" aria-label="当前项目">
        <RouterLink v-for="tab in primaryTabs" :key="tab.key" :to="`/projects/${project.id}/${tab.key}`" :class="{selected:activeTab(tab.key)}" :aria-current="activeTab(tab.key)?'page':undefined">{{tab.label}}</RouterLink>
        <el-dropdown trigger="click" @command="openMoreTab">
          <button type="button" class="more-trigger" :class="{selected:moreActive}">更多</button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="tab in moreTabs" :key="tab.key" :command="tab.key" :class="{selected:activeTab(tab.key)}" :aria-current="activeTab(tab.key)?'page':undefined">{{tab.label}}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </nav>
      <el-alert v-if="project&&context.error" :title="`项目刷新失败：${context.error}`" type="warning" :closable="false"><el-button link @click="context.load(project.id,true)">重试刷新</el-button></el-alert>
      <el-alert v-if="project?.status==='ARCHIVED'" class="archive-banner" title="项目已归档，当前为只读历史" type="info" :closable="false"><RouterLink v-if="isPm" :to="`/projects/${project.id}/settings`">前往设置恢复项目</RouterLink></el-alert>
      <section v-if="hasProject&&projectError" class="surface empty" role="alert"><h2>暂时无法打开这个项目或工作项</h2><p>{{projectError}}</p><el-button @click="retry">重试</el-button><RouterLink to="/projects"><el-button type="primary">返回项目列表</el-button></RouterLink></section>
      <el-skeleton v-else-if="hasProject&&(resolving||!project)" :rows="7" animated class="surface" />
      <RouterView v-else :key="route.path" />
    </main>
  </div>
</template>

<style scoped>
.menu-button { display: none; }
.more-trigger { font: inherit; color: inherit; background: transparent; border: 0; padding: 0 12px; min-height: 36px; cursor: pointer; }
.drawer-nav { display: flex; flex-direction: column; gap: 4px; }
.drawer-nav a { display: flex; align-items: center; justify-content: space-between; min-height: 44px; padding: 0 12px; border-radius: 6px; color: inherit; text-decoration: none; }
.drawer-nav a.router-link-active { font-weight: 600; background: var(--el-fill-color-light); }
.drawer-user { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--el-border-color-lighter); }
.drawer-user small { display: block; color: var(--el-text-color-secondary); }
.drawer-logout { min-height: 44px; }

@media (max-width: 600px) {
  .app-shell { display: block; }
  .sidebar { display: none; }
  .content { margin-left: 0; min-width: 0; }
  .app-topbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  .menu-button { display: inline-flex; min-height: 44px; min-width: 44px; }
  .topbar-crumbs { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .topbar-crumbs a { display: inline-flex; align-items: center; min-height: 44px; }
  .project-tabs { display: flex; gap: 2px; overflow-x: visible; }
  .project-tabs > a, .project-tabs > .el-dropdown { flex: 1 1 0; min-width: 0; }
  .project-tabs > a, .more-trigger { display: flex; align-items: center; justify-content: center; width: 100%; min-height: 44px; padding: 0 4px; font-size: 14px; white-space: nowrap; }
  .archive-banner a { display: inline-flex; align-items: center; min-height: 44px; }
}
</style>
