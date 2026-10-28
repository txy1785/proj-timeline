<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, upload } from '../api/client'
import type { Attachment, AttachmentOwnerType } from '../api/types'
import { attachmentMarkdown } from '../utils/markdown'
import { MAX_ATTACHMENT_BYTES, previewKind } from '../utils/attachment'
import MarkdownView from './MarkdownView.vue'

/**
 * Markdown 编辑区：工具条 + 文本域 + 预览。
 * <p>
 * 粘贴/拖入的文件会立即上传为任务附件（分类由 `category` 指定，默认「进展记录附件」），
 * 上传成功后在光标处插入 `attachment:{id}` 占位：图片内联预览，其他文件显示成可点击的超链接。
 * 附件本体进入任务附件列表，编辑区不重复保存文件。
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
}>(), {
  ownerType: 'TASK',
  ownerId: 0,
  category: 'TASK_RECORD',
  canUpload: false,
  disabled: false,
  placeholder: '记录本次进展、问题或结果，支持 Markdown',
  ariaLabel: 'Markdown 内容',
  rows: 9
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'uploaded', attachment: Attachment): void
  (e: 'removed', attachmentId: number): void
}>()

const textareaRef = ref<HTMLTextAreaElement>()
const fileInputRef = ref<HTMLInputElement>()
const mode = ref<'edit' | 'preview'>('edit')
const uploading = ref(0)
const uploaded = ref<Attachment[]>([])
const removing = ref<number[]>([])

const tools = [
  { label: 'H', title: '二级标题', run: () => linePrefix('## ', '标题') },
  { label: 'B', title: '加粗', run: () => surround('**', '**', '加粗文字') },
  { label: 'I', title: '斜体', run: () => surround('*', '*', '斜体文字') },
  { label: 'S', title: '删除线', run: () => surround('~~', '~~', '删除线') },
  { label: '“”', title: '引用', run: () => linePrefix('> ', '引用内容') },
  { label: '•', title: '无序列表', run: () => linePrefix('- ', '列表项') },
  { label: '1.', title: '有序列表', run: () => linePrefix('1. ', '列表项') },
  { label: '`', title: '行内代码', run: () => surround('`', '`', '代码') },
  { label: '</>', title: '代码块', run: () => surround('```\n', '\n```', '代码') },
  { label: '链接', title: '插入链接', run: () => surround('[', '](https://)', '链接文字') },
  { label: '表格', title: '插入表格', run: () => insertAtCursor('| 列1 | 列2 |\n| --- | --- |\n| 内容 | 内容 |\n') }
]

function textarea(): HTMLTextAreaElement | undefined {
  return textareaRef.value ?? undefined
}

function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
}

/** 写入新内容并把选区放回指定位置；emit 后等 Vue 更新完再恢复光标。 */
function apply(value: string, start: number, end: number) {
  emit('update:modelValue', value)
  void nextTick(() => {
    const element = textarea()
    if (!element) return
    element.focus()
    element.setSelectionRange(start, end)
  })
}

function surround(before: string, after = before, placeholder = '') {
  const element = textarea()
  if (!element || props.disabled) return
  const start = element.selectionStart
  const end = element.selectionEnd
  const selected = props.modelValue.slice(start, end) || placeholder
  const value = props.modelValue.slice(0, start) + before + selected + after + props.modelValue.slice(end)
  apply(value, start + before.length, start + before.length + selected.length)
}

function linePrefix(prefix: string, placeholder: string) {
  const element = textarea()
  if (!element || props.disabled) return
  const start = element.selectionStart
  const end = element.selectionEnd
  const lineStart = props.modelValue.lastIndexOf('\n', start - 1) + 1
  const block = props.modelValue.slice(lineStart, end) || placeholder
  const prefixed = block.split('\n').map(line => (line.startsWith(prefix) ? line : prefix + line)).join('\n')
  const value = props.modelValue.slice(0, lineStart) + prefixed + props.modelValue.slice(end)
  apply(value, lineStart + prefixed.length, lineStart + prefixed.length)
}

function insertAtCursor(text: string) {
  const element = textarea()
  if (!element || props.disabled) return
  const start = element.selectionStart
  const end = element.selectionEnd
  apply(props.modelValue.slice(0, start) + text + props.modelValue.slice(end), start + text.length, start + text.length)
}

function insertAttachment(attachment: Attachment) {
  const image = previewKind(attachment.originalName) === 'image'
  const element = textarea()
  if (!element) return
  // 图片按块级占位插入，避免粘在上一行文字后面被当成行内图片；文件链接保持行内
  const before = props.modelValue.slice(0, element.selectionStart)
  const leading = image && before && !before.endsWith('\n') ? '\n' : ''
  insertAtCursor(`${leading}${attachmentMarkdown(attachment.originalName, attachment.id, image)}${image ? '\n' : ''}`)
}

function openFilePicker() {
  if (props.disabled || !props.canUpload) return
  fileInputRef.value?.click()
}

function onPickFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (files.length) void acceptFiles(files)
}

async function onPaste(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.files || [])
  if (!files.length) return
  if (props.disabled) { ElMessage.warning('正在保存，请稍后再粘贴附件'); return }
  event.preventDefault()
  // 预览态也能直接粘贴：切回编辑后在当前光标处插入
  if (mode.value === 'preview') { mode.value = 'edit'; await nextTick() }
  await acceptFiles(files)
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
  await acceptFiles(Array.from(event.dataTransfer?.files || []))
}

/** 撤销本次粘贴：删除刚上传的任务附件，并从正文去掉它的全部占位。 */
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
    uploaded.value = uploaded.value.filter(item => item.id !== attachment.id)
    // 转义感知：文件名里的 [ ] 在插入时已转义为 \[ \]，不能用近似正则；只删占位及其后的一个换行
    const pattern = new RegExp(`!?\\[(?:[^\\]\\\\]|\\\\.)*\\]\\(attachment:${attachment.id}\\)\\n?`, 'g')
    const next = props.modelValue.replace(pattern, '')
    if (next !== props.modelValue) emit('update:modelValue', next)
    emit('removed', attachment.id)
    ElMessage.success('附件已移除')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    removing.value = removing.value.filter(id => id !== attachment.id)
  }
}

async function acceptFiles(files: File[]) {
  if (props.disabled) { ElMessage.warning('正在保存，请稍后再添加附件'); return }
  if (!props.canUpload || !props.ownerId) { ElMessage.warning('当前没有上传附件的权限'); return }
  const accepted = files.filter(file => {
    if (file.size > MAX_ATTACHMENT_BYTES) { ElMessage.warning(`「${file.name}」超过 50MB，未能上传`); return false }
    return true
  })
  if (!accepted.length) return
  uploading.value += accepted.length
  for (const file of accepted) {
    try {
      const body = new FormData()
      body.append('ownerType', props.ownerType)
      body.append('ownerId', String(props.ownerId))
      body.append('category', props.category)
      body.append('file', file)
      const attachment = await upload<Attachment>('/attachments', body)
      uploaded.value = [attachment, ...uploaded.value]
      insertAttachment(attachment)
      emit('uploaded', attachment)
    } catch (error) {
      ElMessage.error(`${file.name} 上传失败：${(error as Error).message}`)
    } finally {
      uploading.value -= 1
    }
  }
}

// 外部清空草稿（保存成功或切换任务）时，同步丢弃已上传预览，避免串到下一份记录
watch(() => props.modelValue, value => {
  if (!value.trim()) uploaded.value = []
})
</script>

<template>
  <div class="md-editor" :class="{ 'is-disabled': disabled }" tabindex="-1" @paste="onPaste" @dragover="onDragOver" @drop="onDrop">
    <div class="md-editor-bar">
      <div class="md-tools" role="toolbar" aria-label="Markdown 工具栏">
        <button v-for="tool in tools" :key="tool.title" type="button" class="md-tool" :title="tool.title" :aria-label="tool.title" :disabled="disabled || mode === 'preview'" @mousedown.prevent @click="tool.run">{{ tool.label }}</button>
        <button type="button" class="md-tool md-tool-file" :disabled="disabled || !canUpload || mode === 'preview'" :title="canUpload ? '插入附件（也可直接粘贴）' : '当前没有上传附件的权限'" aria-label="插入附件" @mousedown.prevent @click="openFilePicker">附件</button>
      </div>
      <div class="md-modes">
        <button type="button" class="md-mode" :class="{ active: mode === 'edit' }" @click="mode = 'edit'">编辑</button>
        <button type="button" class="md-mode" :class="{ active: mode === 'preview' }" @click="mode = 'preview'">预览</button>
      </div>
    </div>

    <div v-show="mode === 'edit'" class="md-editor-pane">
      <textarea ref="textareaRef" class="md-textarea" :value="modelValue" :rows="rows" :disabled="disabled" :placeholder="placeholder" :aria-label="ariaLabel" @input="onInput" />
      <p class="md-editor-hint">
        支持 Markdown；直接 <strong>Ctrl+V 粘贴截图或文件</strong> 会自动上传到本任务附件，并在正文插入占位：图片预览、文件超链接。
      </p>
      <p v-if="uploading" class="md-editor-uploading" role="status">正在上传 {{ uploading }} 个文件…</p>
    </div>

    <div v-if="uploaded.length" class="md-uploads">
      <span class="md-uploads-label">本次上传</span>
      <span v-for="file in uploaded" :key="file.id" class="md-upload-chip">
        <span class="md-upload-name" :title="file.originalName">{{ file.originalName }}</span>
        <button type="button" class="md-upload-remove" :aria-label="`移除 ${file.originalName}`" :title="`移除 ${file.originalName}`" :disabled="disabled || !canUpload || removing.includes(file.id)" @click="removeUpload(file)">×</button>
      </span>
    </div>

    <div v-if="mode === 'preview'" class="md-editor-preview">
      <MarkdownView v-if="modelValue.trim()" :content="modelValue" :attachments="uploaded" />
      <p v-else class="muted">还没有内容，切回「编辑」开始写 Markdown。</p>
    </div>

    <input ref="fileInputRef" type="file" multiple class="md-editor-file" @change="onPickFiles">
  </div>
</template>

<style scoped>
.md-editor { border:1px solid var(--line); border-radius:8px; background:#fff; overflow:hidden; }
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
.md-textarea { display:block; width:100%; min-height:180px; max-height:52vh; padding:12px 14px; border:0; outline:none; resize:vertical; font-family:inherit; font-size:14px; line-height:1.7; color:var(--navy); background:transparent; }
.md-textarea:disabled { color:var(--muted); cursor:not-allowed; }
.md-editor-hint { margin:0; padding:0 14px 10px; font-size:12px; color:var(--muted); line-height:1.6; }
.md-editor-uploading { margin:0; padding:0 14px 10px; font-size:12px; color:var(--blue); }
.md-uploads { display:flex; align-items:center; flex-wrap:wrap; gap:6px; padding:0 14px 10px; }
.md-uploads-label { font-size:12px; color:var(--muted); }
.md-upload-chip { display:inline-flex; align-items:center; gap:2px; max-width:100%; padding:1px 4px 1px 8px; border:1px solid var(--line); border-radius:12px; background:#f8fafd; font-size:12px; color:#43546e; }
.md-upload-name { max-width:180px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.md-upload-remove { border:0; background:transparent; color:var(--muted); cursor:pointer; padding:0 4px; font-size:14px; line-height:1; }
.md-upload-remove:hover:not(:disabled) { color:var(--danger); }
.md-upload-remove:disabled { opacity:.5; cursor:not-allowed; }
.md-editor-preview { min-height:180px; max-height:52vh; overflow:auto; padding:12px 14px; }
.md-editor-file { display:none; }
</style>
