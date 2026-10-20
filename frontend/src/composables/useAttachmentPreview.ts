import { ref } from 'vue'
import type { Attachment } from '../api/types'

/** 附件预览弹窗的开关与当前文件，配合 `AttachmentPreview` 使用。 */
export function useAttachmentPreview() {
  const previewOpen = ref(false)
  const previewItem = ref<Attachment | null>(null)

  function openPreview(row: Attachment) {
    previewItem.value = row
    previewOpen.value = true
  }

  return { previewOpen, previewItem, openPreview }
}
