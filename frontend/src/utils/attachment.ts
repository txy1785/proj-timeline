import { download, http } from '../api/client'
import type { Attachment } from '../api/types'

/** 文件大小展示：B / KB / MB，保留一位小数。 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 下载附件：blob 方式携带 Authorization，文件名使用原始文件名。 */
export async function downloadAttachment(row: Pick<Attachment, 'id' | 'originalName'>): Promise<void> {
  await download(`/attachments/${row.id}/content`, row.originalName)
}

/** 可在线预览的类型；Office 旧格式（doc/xls/ppt）、SVG 与压缩包等不支持。 */
export type PreviewKind = 'pdf' | 'image' | 'text' | 'docx' | 'xlsx' | 'none'

/**
 * 图片预览白名单：与后端 `MediaTypes.INLINE_TYPES` 保持一致，**不含 svg**。
 * SVG 里可以带脚本，blob: 文档继承应用源，右键「新标签页打开」即可执行，
 * 因此 SVG 一律不给预览入口，只能下载。
 */
const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp']
/** 与后端上传白名单可接受的文本类扩展名保持一致。 */
const TEXT_EXTENSIONS = ['txt', 'md', 'csv']

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

export function previewKind(name: string): PreviewKind {
  const ext = extensionOf(name)
  if (ext === 'pdf') return 'pdf'
  if (IMAGE_EXTENSIONS.includes(ext)) return 'image'
  if (TEXT_EXTENSIONS.includes(ext)) return 'text'
  if (ext === 'docx') return 'docx'
  if (ext === 'xlsx') return 'xlsx'
  return 'none'
}

/** 预览时强制给 Blob 一个浏览器能识别的类型，避免 octet-stream 导致渲染失败。 */
const PREVIEW_MIME: Record<Exclude<PreviewKind, 'none'>, string> = {
  pdf: 'application/pdf',
  image: 'image/png',
  text: 'text/plain;charset=utf-8',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
}

const IMAGE_MIME: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp'
}

const mimeFor = (name: string): string => {
  const kind = previewKind(name)
  if (kind === 'none') return ''
  if (kind === 'image') return IMAGE_MIME[extensionOf(name)] || 'image/png'
  return PREVIEW_MIME[kind]
}

export async function fetchAttachmentBlob(row: Pick<Attachment, 'id' | 'originalName'>): Promise<Blob> {
  const response = await http.request<Blob>({
    method: 'GET',
    url: `/attachments/${row.id}/content`,
    responseType: 'blob',
    timeout: 300000
  })
  const mime = mimeFor(row.originalName)
  return mime ? new Blob([response.data], { type: mime }) : response.data
}
