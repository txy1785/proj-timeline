import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '../api/client'
import type { User } from '../api/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(sessionStorage.getItem('timeline-token'))
  const saved = sessionStorage.getItem('timeline-user')
  const user = ref<User | null>(saved ? JSON.parse(saved) as User : null)
  const authenticated = computed(() => Boolean(token.value && user.value))
  const isAdmin = computed(() => user.value?.globalRole === 'ADMIN')
  const isViewer = computed(() => user.value?.globalRole === 'VIEWER')

  async function login(username: string, password: string) {
    const data = await api<{ token: string; user: User }>('POST', '/auth/login', { username, password })
    token.value = data.token; user.value = data.user
    sessionStorage.setItem('timeline-token', data.token)
    sessionStorage.setItem('timeline-user', JSON.stringify(data.user))
  }
  async function refresh() {
    if (!token.value) return
    user.value = await api<User>('GET', '/auth/me')
    sessionStorage.setItem('timeline-user', JSON.stringify(user.value))
  }
  function logout() { token.value = null; user.value = null; sessionStorage.clear() }
  return { token, user, authenticated, isAdmin, isViewer, login, refresh, logout }
})
