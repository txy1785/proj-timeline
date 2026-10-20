import { download } from '../api/client'
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
