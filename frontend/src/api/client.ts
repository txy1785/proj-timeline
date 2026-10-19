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

/** 上传文件：multipart 请求不走默认 15s 超时。 */
export async function upload<T>(url: string, form: FormData): Promise<T> {
  const response = await http.request<ApiResponse<T>>({ method: 'POST', url, data: form, timeout: 300000 })
  if (response.data.code !== 0) throw new Error(response.data.msg)
  return response.data.data
}

/** 下载文件：以 blob 拉取，Authorization 头由拦截器自动带上，避免 token 出现在 URL 中。 */
export async function download(url: string, filename: string): Promise<void> {
  const response = await http.request<Blob>({ method: 'GET', url, responseType: 'blob', timeout: 300000 })
  const objectUrl = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(objectUrl)
}
