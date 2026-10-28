<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Attachment } from '../api/types'
import { downloadAttachment, fetchAttachmentBlob, previewKind } from '../utils/attachment'
import { renderMarkdown } from '../utils/markdown'
import { useAttachmentPreview } from '../composables/useAttachmentPreview'
import AttachmentPreview from './AttachmentPreview.vue'

/**
 * Markdown 展示组件：渲染正文，并把 `attachment:{id}` 占位换成可看的图片或可点的文件链接。
 * 附件本体仍由 `/attachments/{id}/content` 按权限读取，图片先取 Blob 再赋给 `<img>`，不把 token 放进 URL。
 */
const props = defineProps<{ content: string; attachments: Attachment[] }>()
/** 记录列表里的图片按需加载，超过该体积不在正文内联，改为点击后走预览/下载。 */
const MAX_INLINE_IMAGE_BYTES = 20 * 1024 * 1024

const host = ref<HTMLElement>()
const { previewOpen, previewItem, openPreview } = useAttachmentPreview()
const html = computed(() => renderMarkdown(props.content))
const key = computed(() => `${props.content}\u0000${props.attachments.map(item => item.id).join(',')}`)

let generation = 0
let blobUrls: string[] = []

function releaseBlobs() {
  blobUrls.forEach(url => URL.revokeObjectURL(url))
  blobUrls = []
}

function attachmentOf(id: number) {
  return props.attachments.find(item => item.id === id)
}

async function hydrateImages() {
  const current = ++generation
  releaseBlobs()
  await nextTick()
  if (!host.value) return
  const containers = host.value.querySelectorAll<HTMLElement>('.md-image[data-attachment-id]')
  for (const container of containers) {
    const id = Number(container.dataset.attachmentId)
    const attachment = attachmentOf(id)
    if (!attachment) {
      container.innerHTML = '<span class="md-image-state">附件已删除</span>'
      continue
    }
    if (previewKind(attachment.originalName) !== 'image') {
      const link = document.createElement('a')
      link.className = 'md-file'
      link.href = '#'
      link.dataset.attachmentId = String(id)
      link.textContent = `附件：${attachment.originalName}`
      container.replaceChildren(link)
      continue
    }
    if (attachment.sizeBytes > MAX_INLINE_IMAGE_BYTES) {
      const link = document.createElement('a')
      link.className = 'md-file'
      link.href = '#'
      link.dataset.attachmentId = String(id)
      link.textContent = `图片较大，点击查看：${attachment.originalName}`
      container.replaceChildren(link)
      continue
    }
    try {
      const blob = await fetchAttachmentBlob(attachment)
      if (current !== generation) return
      const url = URL.createObjectURL(blob)
      blobUrls.push(url)
      const image = document.createElement('img')
      image.src = url
      image.alt = attachment.title || attachment.originalName
      image.title = attachment.originalName
      image.onerror = () => { container.innerHTML = '<span class="md-image-state">图片加载失败</span>' }
      container.replaceChildren(image)
    } catch {
      if (current !== generation) return
      container.innerHTML = '<span class="md-image-state">图片加载失败</span>'
    }
  }
}

function onClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const link = target?.closest<HTMLElement>('.md-file[data-attachment-id]')
  if (!link) return
  event.preventDefault()
  const attachment = attachmentOf(Number(link.dataset.attachmentId))
  if (!attachment) { ElMessage.warning('附件已删除，无法查看'); return }
  if (previewKind(attachment.originalName) !== 'none') { openPreview(attachment); return }
  void downloadAttachment(attachment).catch(error => ElMessage.error((error as Error).message))
}

watch(key, () => { void hydrateImages() }, { immediate: true })
onBeforeUnmount(() => { generation++; releaseBlobs() })
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown 使用 html:false 转义全部用户 HTML，链接协议也由 markdown-it 校验 -->
  <div ref="host" class="markdown-body" @click="onClick" v-html="html" />
  <AttachmentPreview v-model="previewOpen" :attachment="previewItem" />
</template>
