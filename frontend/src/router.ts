import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/', redirect: '/projects' },
    { path: '/projects', component: () => import('./views/ProjectsView.vue') },
    { path: '/projects/:id/timeline', component: () => import('./views/TimelineView.vue') },
    { path: '/projects/:id/modules', component: () => import('./views/ModulesView.vue') },
    { path: '/projects/:projectId/modules/:moduleId', component: () => import('./views/ModuleDetailView.vue') },
    { path: '/nodes/:id', component: () => import('./views/NodeDetailView.vue') },
    { path: '/nodes/:id/kanban', component: () => import('./views/KanbanView.vue') },
    { path: '/workload', component: () => import('./views/WorkloadView.vue') },
    { path: '/notifications', component: () => import('./views/NotificationsView.vue') },
    { path: '/projects/:id/reports', component: () => import('./views/WeeklyReportView.vue') },
    { path: '/projects/:id/settings', component: () => import('./views/ProjectSettingsView.vue') },
    { path: '/projects/:id/docs', component: () => import('./views/ProjectDocsView.vue') },
    { path: '/admin/users', component: () => import('./views/AdminUsersView.vue'), meta: { admin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/projects' }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return '/projects'
  if (to.path === '/login' && auth.authenticated) return '/projects'
})

export default router
