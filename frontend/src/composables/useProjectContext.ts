import { computed, onMounted } from 'vue'
import { useProjectStore } from '../stores/project'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'
import { isProjectManager } from '../utils/permissions'

export function useProjectContext(id: number) {
  const store = useProjectStore()
  const app = useAppStore()
  const auth = useAuthStore()
  const project = computed(() => store.project?.id === id ? store.project : undefined)
  const members = computed(() => store.projectId === id ? store.members : [])
  const loading = computed(() => store.projectId !== id || store.loading)
  const error = computed(() => store.projectId === id ? store.error : '')
  const businessDate = computed(() => app.businessDate)
  const isPm = computed(() => isProjectManager(auth.user, members.value))
  const canCreateWorkItem = computed(() => !loading.value && !error.value && project.value?.status === 'ACTIVE'
    && !auth.isViewer && (isPm.value || project.value.modules.some(module => module.ownerId === auth.user?.id)))
  const refresh = () => store.load(id, true)
  onMounted(() => { void store.load(id) })
  return { project, members, loading, error, businessDate, isPm, canCreateWorkItem, refresh }
}
