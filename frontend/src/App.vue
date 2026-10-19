<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const showShell = computed(() => route.path !== '/login')
const projectId = computed(() => route.params.id)

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <RouterView v-if="!showShell" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/projects"><span class="brand-mark">时</span><span>项目时间线</span></RouterLink>
      <nav>
        <RouterLink to="/projects">项目总览</RouterLink>
        <RouterLink to="/workload">人员负载</RouterLink>
        <RouterLink to="/notifications">通知中心</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/users">账号管理</RouterLink>
        <template v-if="projectId">
          <div class="nav-caption">当前项目</div>
          <RouterLink :to="`/projects/${projectId}/timeline`">项目时间线</RouterLink>
          <RouterLink :to="`/projects/${projectId}/modules`">模块视图</RouterLink>
          <RouterLink :to="`/projects/${projectId}/reports`">项目周报</RouterLink>
          <RouterLink :to="`/projects/${projectId}/settings`">项目设置</RouterLink>
        </template>
      </nav>
      <div class="sidebar-user">
        <div><strong>{{ auth.user?.name }}</strong><small>{{ auth.user?.globalRole }}</small></div>
        <el-button text @click="logout">退出</el-button>
      </div>
    </aside>
    <main class="content"><RouterView /></main>
  </div>
</template>
