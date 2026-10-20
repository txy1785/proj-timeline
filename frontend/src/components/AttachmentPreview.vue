<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Attachment } from '../api/types'
import { downloadAttachment, fetchAttachmentBlob, previewKind } from '../utils/attachment'
import type { PreviewKind } from '../utils/attachment'

const props = defineProps<{ modelValue: boolean; attachment: Attachment | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const MAX_TEXT = 300000
const MAX_ROWS = 200

const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) })
const loading = ref(false)
const error = ref('')
const kind = ref<PreviewKind>('none')
const blobUrl = ref('')
const text = ref('')
const docxHost = ref<HTMLElement | null>(null)
const sheets = ref<{ name: string; rows: string[][] }[]>([])
const activeSheet = ref('0')

const currentSheet = computed(() => sheets.value[Number(activeSheet.value)])

async function load(file: Attachment) {
  cleanup()
  kind.value = previewKind(file.originalName)
  if (kind.value === 'none') return
  loading.value = true
  try {
    const blob = await fetchAttachmentBlob(file)
    if (kind.value === 'image' || kind.value === 'pdf') {
      blobUrl.value = URL.createObjectURL(blob)
    } else if (kind.value === 'text') {
      text.value = (await blob.text()).slice(0, MAX_TEXT)
    } else if (kind.value === 'docx') {
      await nextTick()
      const { renderAsync } = await import('docx-preview')
      if (!docxHost.value) throw new Error('预览容器未就绪')
      docxHost.value.innerHTML = ''
      await renderAsync(await blob.arrayBuffer(), docxHost.value, undefined, { breakPages: true, inWrapper: true })
    } else if (kind.value === 'xlsx') {
      const excel = await import('read-excel-file/browser')
      // 一次读全部工作表，返回 [{ sheet, data }]
      const workbook = await excel.default(blob)
      sheets.value = workbook.slice(0, 5).map(item => ({
        name: item.sheet,
        rows: item.data.slice(0, MAX_ROWS).map(row =>
          row.map(cell => (cell === null || cell === undefined ? '' : String(cell)))
        )
      }))
      activeSheet.value = '0'
    }
  } catch (caught) {
    error.value = (caught as Error).message || '预览加载失败'
  } finally {
    loading.value = false
  }
}

function cleanup() {
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value)
  blobUrl.value = ''
  text.value = ''
  error.value = ''
  sheets.value = []
  if (docxHost.value) docxHost.value.innerHTML = ''
}

async function save() {
  if (!props.attachment) return
  try {
    await downloadAttachment(props.attachment)
  } catch (caught) {
    ElMessage.error((caught as Error).message)
  }
}

watch(
  () => [props.modelValue, props.attachment?.id] as const,
  ([open, id]) => {
    if (open && id && props.attachment) void load(props.attachment)
    else cleanup()
  }
)
</script>

<template>
  <el-dialog v-model="visible" :title="attachment?.title || '文件预览'" width="900px" top="5vh" destroy-on-close @closed="cleanup">
    <div v-loading="loading" class="preview-body">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <img v-else-if="kind === 'image'" :src="blobUrl" class="preview-image" :alt="attachment?.originalName" />
      <iframe v-else-if="kind === 'pdf'" :src="blobUrl" class="preview-frame" :title="attachment?.originalName" />
      <pre v-else-if="kind === 'text'" class="preview-text">{{ text }}</pre>
      <div v-else-if="kind === 'docx'" ref="docxHost" class="preview-docx" />
      <template v-else-if="kind === 'xlsx'">
        <el-tabs v-model="activeSheet">
          <el-tab-pane v-for="(sheet, index) in sheets" :key="sheet.name" :label="sheet.name" :name="String(index)" />
        </el-tabs>
        <p v-if="!currentSheet?.rows.length" class="muted">该工作表没有数据</p>
        <div v-else class="sheet-wrap">
          <table>
            <tbody>
              <tr v-for="(row, rowIndex) in currentSheet.rows" :key="rowIndex">
                <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="muted hint">最多显示前 {{ MAX_ROWS }} 行、前 5 个工作表。</p>
      </template>
      <el-empty v-else description="该格式暂不支持在线预览，请下载后查看" :image-size="80" />
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" @click="save">下载原文件</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.preview-body { height: 68vh; overflow: auto; }
.preview-image { display: block; max-width: 100%; margin: 0 auto; }
.preview-frame { width: 100%; height: 100%; border: 1px solid var(--line); border-radius: 8px; }
.preview-text { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.7; }
.preview-docx { background: #f5f6f8; padding: 12px; border-radius: 8px; }
.preview-docx :deep(.docx-wrapper) { background: transparent; padding: 0; }
.sheet-wrap { overflow: auto; max-height: 58vh; border: 1px solid var(--line); border-radius: 8px; }
.sheet-wrap table { border-collapse: collapse; font-size: 12px; }
.sheet-wrap td { border: 1px solid var(--line); padding: 4px 8px; white-space: nowrap; }
.sheet-wrap tr:first-child td { background: rgba(37, 99, 235, 0.06); font-weight: 600; }
.hint { margin: 8px 0 0; font-size: 12px; }
</style>
