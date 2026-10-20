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
  async (error) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem('timeline-token')
      sessionStorage.removeItem('timeline-user')
      if (location.pathname !== '/login') location.assign('/login')
    }
    return Promise.reject(new Error(await messageOf(error)))
  }
)

/** 错误提示：blob 下载失败时服务端仍返回 {code,msg} 的 JSON，需要先读文本再解析。 */
async function messageOf(error: { response?: { data?: unknown }; message?: string }): Promise<string> {
  const data = error.response?.data
  if (data instanceof Blob) {
    try {
      const text = await data.text()
      const parsed = JSON.parse(text) as ApiResponse<unknown>
      if (parsed?.msg) return parsed.msg
    } catch { /* 非 JSON 错误体，回退到通用提示 */ }
    return error.message || '请求失败'
  }
  const response = data as ApiResponse<unknown> | undefined
  return response?.msg || error.message || '请求失败'
}

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
  // 立即 revoke 会中断部分浏览器的下载，延迟释放
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}
