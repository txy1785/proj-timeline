<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DOMPurify from 'dompurify'
import { api, upload } from '../api/client'
import type { Attachment, AttachmentOwnerType } from '../api/types'
import { renderMarkdown } from '../utils/markdown'
import { downloadAttachment, fetchAttachmentBlob, MAX_ATTACHMENT_BYTES, previewKind } from '../utils/attachment'
import { createMarkdownTurndown, removeAttachmentPlaceholders } from '../utils/editableMarkdown'
import { useAttachmentPreview } from '../composables/useAttachmentPreview'
import AttachmentPreview from './AttachmentPreview.vue'

/**
 * 单栏 Markdown 编辑区：编辑区直接显示渲染结果（标题、列表、图片、文件名链接），
 * 输入时用 turndown 实时序列化回 Markdown 保存；需要直接改语法时可切到「源码」。
 * 粘贴/拖入的文件立即上传为任务附件，图片内联展示，其他文件只显示文件名、点击查看。
 */
const props = withDefaults(defineProps<{
  modelValue: string
  ownerType?: AttachmentOwnerType
  ownerId?: number
  category?: string
  canUpload?: boolean
  disabled?: boolean
  placeholder?: string
  ariaLabel?: string
  rows?: number
  /** 已保存记录引用的任务附件；用于解析正文里已有的图片与文件链接。 */
  attachments?: Attachment[]
}>(), {
  ownerType: 'TASK',
  ownerId: 0,
  category: 'TASK_RECORD',
  canUpload: false,
  disabled: false,
  placeholder: '记录本次进展、问题或结果，支持 Markdown',
  ariaLabel: 'Markdown 内容',
  rows: 9,
  attachments: () => []
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'uploaded', attachment: Attachment): void
  (e: 'removed', attachmentId: number): void
  (e: 'uploading', count: number): void
}>()

const MAX_INLINE_IMAGE_BYTES = 20 * 1024 * 1024
const turndown = createMarkdownTurndown()
const surfaceRef = ref<HTMLDivElement>()
const sourceRef = ref<HTMLTextAreaElement>()
const fileInputRef = ref<HTMLInputElement>()
const sourceMode = ref(false)
const uploading = ref(0)
const uploaded = ref<Attachment[]>([])
const removing = ref<number[]>([])
const composing = ref(false)
const { previewOpen, previewItem, openPreview } = useAttachmentPreview()

const allAttachments = computed(() => {
  const merged = new Map<number, Attachment>()
  for (const item of props.attachments) merged.set(item.id, item)
  for (const item of uploaded.value) merged.set(item.id, item)
  return merged
})

const blobCache = new Map<number, string>()
let disposed = false
let hydrationToken = 0
let lastMarkdown = props.modelValue
let savedRange: Range | null = null

function attachmentOf(id: number) {
  return allAttachments.value.get(id)
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch] || ch))
}

function showImage(container: HTMLElement, attachment: Attachment, url: string) {
  const image = document.createElement('img')
  image.src = url
  image.alt = attachment.title || attachment.originalName
  image.title = attachment.originalName
  image.onerror = () => { container.textContent = '图片加载失败' }
  container.replaceChildren(image)
}

async function hydrateImages() {
  const host = surfaceRef.value
  if (!host) return
  const token = ++hydrationToken
  const containers = host.querySelectorAll<HTMLElement>('.md-image[data-attachment-id]')
  for (const container of containers) {
    container.setAttribute('contenteditable', 'false')
    if (container.querySelector('img')) continue
    const id = Number(container.dataset.attachmentId)
    const attachment = attachmentOf(id)
    if (!attachment) { container.textContent = '附件已删除'; continue }
    if (previewKind(attachment.originalName) !== 'image' || attachment.sizeBytes > MAX_INLINE_IMAGE_BYTES) {
      const link = document.createElement('a')
      link.className = 'md-file'
      link.href = '#'
      link.dataset.attachmentId = String(id)
      link.setAttribute('contenteditable', 'false')
      link.textContent = previewKind(attachment.originalName) === 'image'
        ? `图片较大，点击查看：${attachment.originalName}`
        : `附件：${attachment.originalName}`
      container.replaceWith(link)
      continue
    }
    const cached = blobCache.get(id)
    if (cached) { showImage(container, attachment, cached); continue }
    try {
      const blob = await fetchAttachmentBlob(attachment)
      const url = URL.createObjectURL(blob)
      if (disposed) { URL.revokeObjectURL(url); return }
      blobCache.set(id, url)
      if (token !== hydrationToken) return
      showImage(container, attachment, url)
    } catch {
      if (token === hydrationToken) container.textContent = '图片加载失败'
    }
  }
}

function setSurfaceContent(value: string) {
  const host = surfaceRef.value
  if (!host) return
  lastMarkdown = value
  host.innerHTML = value.trim() ? renderMarkdown(value) : ''
  host.querySelectorAll<HTMLElement>('.md-file[data-attachment-id]').forEach(element => element.setAttribute('contenteditable', 'false'))
  void hydrateImages()
}

function serialize() {
  const host = surfaceRef.value
  if (!host) return
  const markdown = turndown.turndown(host.innerHTML).replace(/\u00a0/g, ' ').trim()
  if (markdown === lastMarkdown) return
  lastMarkdown = markdown
  emit('update:modelValue', markdown)
}

function onInput() {
  if (composing.value) return
  const host = surfaceRef.value
  if (host && (host.innerHTML === '<br>' || host.innerHTML === '<div><br></div>')) host.innerHTML = ''
  serialize()
}

function onCompositionEnd() {
  composing.value = false
  serialize()
}

function onSourceInput(event: Event) {
  const value = (event.target as HTMLTextAreaElement).value
  lastMarkdown = value
  emit('update:modelValue', value)
}

function onFocus() {
  try { document.execCommand('defaultParagraphSeparator', false, 'p') } catch { /* 老浏览器忽略 */ }
}

function saveSelection() {
  const host = surfaceRef.value
  const selection = window.getSelection()
  if (!host || !selection?.rangeCount || !host.contains(selection.anchorNode)) return
  savedRange = selection.getRangeAt(0).cloneRange()
}

function restoreSelection() {
  const host = surfaceRef.value
  if (!host) return
  host.focus()
  const selection = window.getSelection()
  if (savedRange && host.contains(savedRange.commonAncestorContainer)) {
    selection?.removeAllRanges()
    selection?.addRange(savedRange)
    return
  }
  if (selection?.rangeCount && host.contains(selection.anchorNode)) return
  const range = document.createRange()
  range.selectNodeContents(host)
  range.collapse(false)
  selection?.removeAllRanges()
  selection?.addRange(range)
}

function insertHtml(html: string) {
  if (props.disabled) return
  restoreSelection()
  const selection = window.getSelection()
  if (!selection?.rangeCount) return
  const range = selection.getRangeAt(0)
  range.deleteContents()
  const template = document.createElement('template')
  template.innerHTML = html
  const fragment = template.content
  const last = fragment.lastChild
  range.insertNode(fragment)
  if (last) {
    const after = document.createRange()
    after.setStartAfter(last)
    after.collapse(true)
    selection.removeAllRanges()
    selection.addRange(after)
  }
  savedRange = null
  serialize()
}

function currentBlock(): HTMLElement | null {
  const host = surfaceRef.value
  const selection = window.getSelection()
  if (!host || !selection?.rangeCount) return null
  let node: Node | null = selection.anchorNode
  while (node && node !== host) {
    if (node.nodeType === Node.ELEMENT_NODE) {
      const element = node as HTMLElement
      if (['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'LI', 'BLOCKQUOTE', 'PRE', 'TD', 'TH'].includes(element.tagName)) return element
    }
    node = node.parentNode
  }
  return null
}

function execute(command: string, value?: string) {
  if (props.disabled || sourceMode.value) return
  restoreSelection()
  document.execCommand(command, false, value)
  serialize()
}

function normalizeTextRange(range: Range): boolean {
  if (range.startContainer.nodeType === Node.ELEMENT_NODE) {
    let node: Node | null = range.startContainer.firstChild
    while (node && node.nodeType !== Node.TEXT_NODE) node = node.firstChild
    if (!node) return false
    range.setStart(node, 0)
  }
  if (range.endContainer.nodeType === Node.ELEMENT_NODE) {
    let node: Node | null = range.endContainer.lastChild
    while (node && node.nodeType !== Node.TEXT_NODE) node = node.lastChild
    if (!node) return false
    range.setEnd(node, (node.textContent || '').length)
  }
  return true
}

function applyInline(tag: 'strong' | 'em' | 's') {
  if (props.disabled || sourceMode.value) return
  const selection = window.getSelection()
  if (!selection?.rangeCount) return
  const command = tag === 'strong' ? 'bold' : tag === 'em' ? 'italic' : 'strikeThrough'
  if (selection.isCollapsed) {
    // 光标状态：允许用户再次点击工具栏关闭加粗/斜体，继续输入普通文字
    restoreSelection()
    document.execCommand(command)
    return
  }
  const range = selection.getRangeAt(0)
  const wrapper = document.createElement(tag)
  try {
    if (!normalizeTextRange(range)) return
    range.surroundContents(wrapper)
    const after = document.createRange()
    after.setStartAfter(wrapper)
    after.collapse(true)
    selection.removeAllRanges()
    selection.addRange(after)
    serialize()
  } catch {
    // 跨多个块选择时 surroundContents 会失败；用原生命令保留块结构，避免整段被替换
    restoreSelection()
    document.execCommand(command)
    serialize()
  }
}

function toggleHeading() {
  const block = currentBlock()
  execute('formatBlock', block?.tagName === 'H2' ? 'p' : 'h2')
}

function toggleQuote() {
  const block = currentBlock()
  execute('formatBlock', block?.tagName === 'BLOCKQUOTE' ? 'p' : 'blockquote')
}

function inlineCode() {
  const selection = window.getSelection()
  const text = selection?.toString() || '代码'
  insertHtml(`<code>${escapeHtml(text)}</code>`)
}

function codeBlock() {
  const selection = window.getSelection()
  const text = selection?.toString() || '代码'
  insertHtml(`<pre><code>${escapeHtml(text)}</code></pre><p><br></p>`)
}

function insertTable() {
  insertHtml('<table><thead><tr><th>列1</th><th>列2</th></tr></thead><tbody><tr><td>内容</td><td>内容</td></tr></tbody></table><p><br></p>')
}

async function insertLink() {
  let url = ''
  try {
    const result = await ElMessageBox.prompt('链接地址（http/https/mailto）', '插入链接', {
      inputValue: 'https://', confirmButtonText: '插入', cancelButtonText: '取消',
      inputPattern: /^(https?:\/\/|mailto:)/i, inputErrorMessage: '请输入 http(s) 或 mailto 链接'
    })
    url = result.value.trim()
  } catch { return }
  const selection = window.getSelection()
  const text = selection?.toString() || url
  insertHtml(`<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(text)}</a>`)
}

function attachmentHtml(attachment: Attachment, image: boolean, url?: string) {
  const name = attachment.originalName
  if (image) {
    return `<span class="md-image" data-attachment-id="${attachment.id}" data-name="${escapeHtml(name)}" contenteditable="false"><img src="${url || ''}" alt="${escapeHtml(name)}" title="${escapeHtml(name)}"></span><br>`
  }
  return `<a class="md-file" href="#" data-attachment-id="${attachment.id}" contenteditable="false" title="${escapeHtml(name)}">${escapeHtml(name)}</a>&nbsp;`
}

function openFilePicker() {
  if (props.disabled || !props.canUpload || sourceMode.value) return
  saveSelection()
  fileInputRef.value?.click()
}

function onPickFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (files.length) void acceptFiles(files)
}

function hasFiles(event: DragEvent) {
  return Array.from(event.dataTransfer?.types || []).includes('Files')
}

function onDragOver(event: DragEvent) {
  if (hasFiles(event)) event.preventDefault()
}

async function onDrop(event: DragEvent) {
  if (!hasFiles(event)) return
  event.preventDefault()
  if (sourceMode.value) await switchSource(false)
  saveSelection()
  await acceptFiles(Array.from(event.dataTransfer?.files || []))
}

async function onPaste(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.files || [])
  if (files.length) {
    if (props.disabled) { ElMessage.warning('正在保存，请稍后再粘贴附件'); return }
    event.preventDefault()
    if (sourceMode.value) await switchSource(false)
    saveSelection()
    await acceptFiles(files)
    return
  }
  if (sourceMode.value) return
  const html = event.clipboardData?.getData('text/html') || ''
  if (!html) return
  event.preventDefault()
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'b', 'em', 'i', 'del', 's', 'code', 'pre', 'ul', 'ol', 'li', 'blockquote', 'h1', 'h2', 'h3', 'h4', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr'],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'class'],
    ALLOW_DATA_ATTR: false
  })
  insertHtml(String(clean))
}

async function acceptFiles(files: File[]) {
  if (props.disabled) { ElMessage.warning('正在保存，请稍后再添加附件'); return }
  if (!props.canUpload || !props.ownerId) { ElMessage.warning('当前没有上传附件的权限'); return }
  if (sourceMode.value) await switchSource(false)
  const accepted = files.filter(file => {
    if (file.size > MAX_ATTACHMENT_BYTES) { ElMessage.warning(`「${file.name}」超过 50MB，未能上传`); return false }
    return true
  })
  if (!accepted.length) return
  uploading.value += accepted.length
  if (!disposed) emit('uploading', uploading.value)
  for (const file of accepted) {
    try {
      const body = new FormData()
      body.append('ownerType', props.ownerType)
      body.append('ownerId', String(props.ownerId))
      body.append('category', props.category)
      body.append('file', file)
      const attachment = await upload<Attachment>('/attachments', body)
      uploaded.value = [attachment, ...uploaded.value]
      emit('uploaded', attachment)
      const image = previewKind(attachment.originalName) === 'image'
      const url = image ? URL.createObjectURL(file) : undefined
      if (url) blobCache.set(attachment.id, url)
      insertHtml(attachmentHtml(attachment, image, url))
    } catch (error) {
      ElMessage.error(`${file.name} 上传失败：${(error as Error).message}`)
    } finally {
      uploading.value -= 1
      if (!disposed) emit('uploading', uploading.value)
    }
  }
}

async function removeUpload(attachment: Attachment) {
  if (props.disabled || removing.value.includes(attachment.id)) return
  try {
    await ElMessageBox.confirm(`从记录正文和任务附件中移除「${attachment.originalName}」？`, '移除附件', {
      type: 'warning', confirmButtonText: '移除', cancelButtonText: '保留'
    })
  } catch { return }
  removing.value = [...removing.value, attachment.id]
  try {
    await api<void>('DELETE', `/attachments/${attachment.id}`)
    if (sourceMode.value) {
      const next = removeAttachmentPlaceholders(props.modelValue, attachment.id)
      lastMarkdown = next
      emit('update:modelValue', next)
    } else {
      surfaceRef.value?.querySelectorAll(`[data-attachment-id="${attachment.id}"]`).forEach(element => element.remove())
      serialize()
    }
    const cached = blobCache.get(attachment.id)
    if (cached) { URL.revokeObjectURL(cached); blobCache.delete(attachment.id) }
    uploaded.value = uploaded.value.filter(item => item.id !== attachment.id)
    emit('removed', attachment.id)
    ElMessage.success('附件已移除')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    removing.value = removing.value.filter(id => id !== attachment.id)
  }
}

function onClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const file = target?.closest<HTMLElement>('a.md-file[data-attachment-id]')
  if (file) {
    event.preventDefault()
    const attachment = attachmentOf(Number(file.dataset.attachmentId))
    if (!attachment) { ElMessage.warning('附件已删除，无法查看'); return }
    if (previewKind(attachment.originalName) !== 'none') { openPreview(attachment); return }
    void downloadAttachment(attachment).catch((error) => ElMessage.error((error as Error).message))
    return
  }
  const link = target?.closest('a[href]')
  if (link) event.preventDefault()
}

async function switchSource(value: boolean) {
  if (props.disabled || sourceMode.value === value) return
  if (value) {
    serialize()
    sourceMode.value = true
    await nextTick()
    sourceRef.value?.focus()
  } else {
    sourceMode.value = false
    await nextTick()
    setSurfaceContent(props.modelValue)
    surfaceRef.value?.focus()
  }
}

watch(() => props.modelValue, value => {
  if (value === lastMarkdown) return
  lastMarkdown = value
  if (!sourceMode.value) setSurfaceContent(value)
})

onMounted(() => setSurfaceContent(props.modelValue))
watch(() => props.attachments, () => { if (!sourceMode.value) void hydrateImages() })
onBeforeUnmount(() => {
  disposed = true
  hydrationToken++
  blobCache.forEach(url => URL.revokeObjectURL(url))
  blobCache.clear()
})
</script>

<template>
  <div class="md-editor" :class="{ 'is-disabled': disabled }" @paste="onPaste" @dragover="onDragOver" @drop="onDrop" @click="onClick">
    <div class="md-editor-bar">
      <div class="md-tools" role="toolbar" aria-label="Markdown 工具栏">
        <button type="button" class="md-tool" title="二级标题" aria-label="二级标题" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="toggleHeading">H</button>
        <button type="button" class="md-tool" title="加粗" aria-label="加粗" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="applyInline('strong')">B</button>
        <button type="button" class="md-tool" title="斜体" aria-label="斜体" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="applyInline('em')">I</button>
        <button type="button" class="md-tool" title="删除线" aria-label="删除线" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="applyInline('s')">S</button>
        <button type="button" class="md-tool" title="引用" aria-label="引用" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="toggleQuote">“”</button>
        <button type="button" class="md-tool" title="无序列表" aria-label="无序列表" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="execute('insertUnorderedList')">•</button>
        <button type="button" class="md-tool" title="有序列表" aria-label="有序列表" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="execute('insertOrderedList')">1.</button>
        <button type="button" class="md-tool" title="行内代码" aria-label="行内代码" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="inlineCode">`</button>
        <button type="button" class="md-tool" title="代码块" aria-label="代码块" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="codeBlock">&lt;/&gt;</button>
        <button type="button" class="md-tool" title="插入链接" aria-label="插入链接" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="insertLink">链接</button>
        <button type="button" class="md-tool" title="插入表格" aria-label="插入表格" :disabled="disabled || sourceMode" @mousedown.prevent="saveSelection" @click="insertTable">表格</button>
        <button type="button" class="md-tool md-tool-file" :disabled="disabled || !canUpload || sourceMode" :title="canUpload ? '插入附件（也可直接粘贴）' : '当前没有上传附件的权限'" aria-label="插入附件" @mousedown.prevent="saveSelection" @click="openFilePicker">附件</button>
      </div>
      <div class="md-modes">
        <button type="button" class="md-mode" :class="{ active: !sourceMode }" :disabled="disabled || uploading > 0" @click="switchSource(false)">编辑</button>
        <button type="button" class="md-mode" :class="{ active: sourceMode }" :disabled="disabled || uploading > 0" title="直接编辑 Markdown 源码（复杂语法）" @click="switchSource(true)">源码</button>
      </div>
    </div>

    <div
      v-if="!sourceMode"
      ref="surfaceRef"
      class="md-surface markdown-body"
      :contenteditable="disabled ? 'false' : 'true'"
      role="textbox"
      aria-multiline="true"
      :aria-label="ariaLabel"
      :data-placeholder="placeholder"
      spellcheck="false"
      @input="onInput"
      @focus="onFocus"
      @compositionstart="composing = true"
      @compositionend="onCompositionEnd"
    />
    <textarea
      v-else
      ref="sourceRef"
      class="md-source-area"
      :value="modelValue"
      :rows="rows"
      :disabled="disabled"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      @input="onSourceInput"
    />

    <div v-if="uploaded.length" class="md-uploads">
      <span class="md-uploads-label">本次上传</span>
      <span v-for="file in uploaded" :key="file.id" class="md-upload-chip">
        <span class="md-upload-name" :title="file.originalName">{{ file.originalName }}</span>
        <button type="button" class="md-upload-remove" :aria-label="`移除 ${file.originalName}`" :title="`移除 ${file.originalName}`" :disabled="disabled || !canUpload || removing.includes(file.id)" @click="removeUpload(file)">×</button>
      </span>
    </div>

    <p class="md-editor-hint">
      选中文字可用工具栏排版；直接 <strong>Ctrl+V 粘贴截图或文件</strong> 会自动上传并插入正文。需要手写 Markdown 时可切到「源码」。
    </p>
    <p v-if="uploading" class="md-editor-uploading" role="status">正在上传 {{ uploading }} 个文件…</p>

    <input ref="fileInputRef" type="file" multiple class="md-editor-file" @change="onPickFiles">
    <AttachmentPreview v-model="previewOpen" :attachment="previewItem" />
  </div>
</template>

<style scoped>
.md-editor { border:1px solid var(--line); border-radius:8px; background:#fff; overflow:hidden; }
.md-editor:focus-within { border-color:#a9c2f2; box-shadow:0 0 0 2px rgba(47,111,237,.08); }
.md-editor.is-disabled { background:#fafbfd; }
.md-editor-bar { display:flex; align-items:center; justify-content:space-between; gap:8px; flex-wrap:wrap; padding:6px 8px; border-bottom:1px solid var(--line); background:#f8fafd; }
.md-tools { display:flex; flex-wrap:wrap; gap:2px; }
.md-tool { min-width:26px; height:26px; padding:0 6px; border:0; border-radius:5px; background:transparent; color:#43546e; font-size:12px; cursor:pointer; }
.md-tool:hover:not(:disabled) { background:#e8eef9; color:var(--blue); }
.md-tool:disabled { opacity:.45; cursor:not-allowed; }
.md-tool-file { margin-left:5px; border-left:1px solid var(--line); border-radius:0 5px 5px 0; padding-left:8px; }
.md-modes { display:flex; gap:2px; }
.md-mode { border:0; background:transparent; padding:4px 10px; border-radius:5px; font-size:12px; color:var(--muted); cursor:pointer; }
.md-mode.active { background:#e8eef9; color:var(--blue); font-weight:600; }
.md-mode:disabled { opacity:.45; cursor:not-allowed; }
.md-surface { min-height:360px; max-height:52vh; overflow:auto; padding:14px 18px; outline:none; font-size:14px; line-height:1.75; color:var(--navy); overflow-wrap:anywhere; }
.md-surface:empty::before { content:attr(data-placeholder); color:var(--muted); }
.md-surface :deep(img) { max-width:100%; }
.md-surface :deep(a) { cursor:pointer; }
.md-surface :deep(table) { border-collapse:collapse; }
.md-surface :deep(th),.md-surface :deep(td) { border:1px solid var(--line); padding:6px 10px; }
.md-surface :deep(pre) { background:#f6f8fc; border:1px solid var(--line); border-radius:8px; padding:12px 14px; overflow:auto; }
.md-surface :deep(code) { background:#f1f4f9; border-radius:4px; padding:1px 6px; font-size:13px; }
.md-surface :deep(pre code) { background:none; padding:0; }
.md-surface :deep(blockquote) { margin:10px 0; padding:6px 14px; border-left:3px solid #c9d6ee; background:#f8fafd; color:#43546e; }
.md-source-area { display:block; width:100%; min-height:min(360px, 52vh); max-height:52vh; padding:14px 18px; border:0; outline:none; resize:vertical; font-family:inherit; font-size:14px; line-height:1.7; color:var(--navy); background:transparent; }
.md-source-area:disabled { color:var(--muted); cursor:not-allowed; }
.md-uploads { display:flex; align-items:center; flex-wrap:wrap; gap:6px; padding:0 18px 10px; }
.md-uploads-label { font-size:12px; color:var(--muted); }
.md-upload-chip { display:inline-flex; align-items:center; gap:2px; max-width:100%; padding:1px 4px 1px 8px; border:1px solid var(--line); border-radius:12px; background:#f8fafd; font-size:12px; color:#43546e; }
.md-upload-name { max-width:180px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.md-upload-remove { border:0; background:transparent; color:var(--muted); cursor:pointer; padding:0 4px; font-size:14px; line-height:1; }
.md-upload-remove:hover:not(:disabled) { color:var(--danger); }
.md-upload-remove:disabled { opacity:.5; cursor:not-allowed; }
.md-editor-hint { margin:0; padding:0 18px 10px; font-size:12px; color:var(--muted); line-height:1.6; }
.md-editor-uploading { margin:0; padding:0 18px 10px; font-size:12px; color:var(--blue); }
.md-editor-file { display:none; }
</style>
