import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { Timeline, User } from '../api/types'
import { useAuthStore } from './auth'
import { useAppStore } from './app'

export const useProjectStore = defineStore('project-context', () => {
  const auth = useAuthStore()
  const app = useAppStore()
  const project = ref<Timeline>()
  const members = ref<User[]>([])
  const projectId = ref(0)
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  let pending: { id: number; promise: Promise<void> } | undefined
  function reset() {
    generation++; project.value = undefined; projectId.value = 0
    members.value = []; error.value = ''; loading.value = false; pending = undefined
  }
  watch(() => auth.user?.id, reset)
  async function load(id: number, force = false) {
    if (!Number.isSafeInteger(id) || id <= 0) return
    if (!force && pending?.id === id) return pending.promise
    if (!force && project.value?.id === id && !error.value) { await app.ensure(); return }
    const current = ++generation
    if (projectId.value !== id) { project.value = undefined; members.value = [] }
    projectId.value = id; loading.value = true; error.value = ''
    const promise = (async () => {
      try {
        const [value, people] = await Promise.all([
          api<Timeline>('GET', `/projects/${id}/timeline`), api<User[]>('GET', `/projects/${id}/members`), app.ensure()
        ])
        if (current !== generation) return
        value.modules.forEach(module => {
          module.projectId = id
          module.nodes?.forEach(node => Object.assign(node, {
            projectId: id, projectStatus: value.status, moduleId: module.id,
            moduleName: module.name, moduleOwnerId: module.ownerId
          }))
        })
        value.projectWorkItems?.forEach(node => Object.assign(node, { projectId: id, projectStatus: value.status }))
        project.value = value; members.value = people
      } catch (e) {
        if (current === generation) {
          error.value = (e as Error).message
          if (e instanceof ApiError && (e.status === 403 || e.status === 404)) { project.value = undefined; members.value = [] }
        }
      } finally {
        if (current === generation) { loading.value = false; pending = undefined }
      }
    })()
    pending = { id, promise }
    return promise
  }
  return { project, members, projectId, loading, error, load, reset }
})
