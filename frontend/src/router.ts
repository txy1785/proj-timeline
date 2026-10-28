import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/', redirect: () => useAuthStore().user?.globalRole === 'USER' ? '/work' : '/projects' },
    { path: '/work', component: () => import('./views/MyWorkView.vue'), meta: { workspace: true } },
    { path: '/projects', component: () => import('./views/ProjectsView.vue') },
    { path: '/projects/:id', redirect: to => `/projects/${to.params.id}/overview` },
    { path: '/projects/:id/overview', component: () => import('./views/ProjectOverviewView.vue'), meta: { projectParam: 'id' } },
    { path: '/projects/:id/work-items', component: () => import('./views/WorkItemsView.vue'), meta: { projectParam: 'id' } },
    { path: '/projects/:id/timeline', component: () => import('./views/TimelineView.vue'), meta: { projectParam: 'id' } },
    { path: '/projects/:id/modules', component: () => import('./views/ModulesView.vue'), meta: { projectParam: 'id' } },
    { path: '/projects/:projectId/modules/:moduleId', component: () => import('./views/ModuleDetailView.vue'), meta: { projectParam: 'projectId' } },
    { path: '/nodes/:id', component: () => import('./views/NodeDetailView.vue'), meta: { nodeParam: 'id' } },
    { path: '/nodes/:nodeId/tasks/:taskId', component: () => import('./views/TaskDetailView.vue'), meta: { nodeParam: 'nodeId' } },
    { path: '/nodes/:id/kanban', component: () => import('./views/KanbanView.vue'), meta: { nodeParam: 'id' } },
    { path: '/workload', component: () => import('./views/WorkloadView.vue') },
    { path: '/notifications', component: () => import('./views/NotificationsView.vue') },
    { path: '/projects/:id/reports', component: () => import('./views/WeeklyReportView.vue'), meta: { projectParam: 'id' } },
    { path: '/projects/:id/settings', component: () => import('./views/ProjectSettingsView.vue'), meta: { projectParam: 'id' } },
    { path: '/projects/:id/docs', component: () => import('./views/ProjectDocsView.vue'), meta: { projectParam: 'id' } },
    { path: '/admin/users', component: () => import('./views/AdminUsersView.vue'), meta: { admin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/projects' }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return '/projects'
  if (to.meta.workspace && auth.isViewer) return '/projects'
  if (to.path === '/login' && auth.authenticated) return auth.user?.globalRole === 'USER' ? '/work' : '/projects'
})

export default router
