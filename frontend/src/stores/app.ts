import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import type { BusinessContext } from '../api/types'
import { useAuthStore } from './auth'

export const useAppStore = defineStore('app-context', () => {
  const auth = useAuthStore()
  const context = ref<BusinessContext>()
  const error = ref('')
  let version = 0
  let pending: Promise<void> | undefined
  const businessDate = computed(() => context.value?.businessDate || '')
  function reset() { version++; context.value = undefined; error.value = ''; pending = undefined }
  watch(() => auth.user?.id, reset)
  async function refresh() {
    if (!auth.authenticated) return
    if (pending) return pending
    const current = version
    pending = (async () => {
      try {
        const result = await api<BusinessContext>('GET', '/context')
        if (current === version) { context.value = result; error.value = '' }
      } catch (e) {
        if (current === version) error.value = (e as Error).message
      } finally { if (current === version) pending = undefined }
    })()
    return pending
  }
  async function ensure() { if (!context.value) await refresh() }
  return { context, businessDate, error, ensure, refresh, reset }
})
