import axios from 'axios'
import type { ApiResponse } from './types'

export const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('timeline-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const response = error.response?.data as ApiResponse<unknown> | undefined
    if (error.response?.status === 401) {
      sessionStorage.removeItem('timeline-token')
      sessionStorage.removeItem('timeline-user')
      if (location.pathname !== '/login') location.assign('/login')
    }
    return Promise.reject(new Error(response?.msg || error.message || '请求失败'))
  }
)

export async function api<T>(method: string, url: string, data?: unknown, params?: unknown): Promise<T> {
  const response = await http.request<ApiResponse<T>>({ method, url, data, params })
  if (response.data.code !== 0) throw new Error(response.data.msg)
  return response.data.data
}
