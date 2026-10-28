<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { api, upload } from '../api/client'
import { downloadAttachment, formatBytes, MAX_ATTACHMENT_BYTES, previewKind } from '../utils/attachment'
import type { Attachment, AttachmentCategory, AttachmentOwnerType } from '../api/types'
import { useAuthStore } from '../stores/auth'
import AttachmentPreview from './AttachmentPreview.vue'
import { useAttachmentPreview } from '../composables/useAttachmentPreview'

const auth = useAuthStore()
const props = withDefaults(defineProps<{
  ownerType: AttachmentOwnerType
  ownerId: number
  canManage: boolean
  archived?: boolean
  showCategoryNav?: boolean
  hint?: string
}>(), { archived: false, showCategoryNav: true, hint: '' })

const categories = ref<AttachmentCategory[]>([])
const files = ref<Attachment[]>([])
const loading = ref(false)
const activeCode = ref('')
const dialog = ref(false)
const uploading = ref(false)
const { previewOpen, previewItem, openPreview } = useAttachmentPreview()
const form = reactive({ code: '', title: '', remark: '', fileName: '', raw: null as File | null })

const ownCategories = computed(() => categories.value.filter(item => item.ownerType === props.ownerType))
const groups = computed(() => Array.from(new Set(ownCategories.value.map(item => item.group))))
const groupCategories = (group: string) => ownCategories.value.filter(item => item.group === group)
const visibleFiles = computed(() => activeCode.value ? files.value.filter(item => item.category === activeCode.value) : files.value)
const canUpload = computed(() => props.canManage && !props.archived)
const activeName = computed(() => ownCategories.value.find(item => item.code === activeCode.value)?.name || '全部文件')
const canDeleteRow = (row: Attachment) => !auth.isViewer && !props.archived && (props.canManage || row.uploaderId === auth.user?.id)

async function load() {
  loading.value = true
  try {
    files.value = await api<Attachment[]>('GET', '/attachments', undefined, { ownerType: props.ownerType, ownerId: props.ownerId })
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  if (categories.value.length) return
  try {
    categories.value = await api<AttachmentCategory[]>('GET', '/attachments/categories')
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function pickCategory(code: string) { activeCode.value = activeCode.value === code ? '' : code }

function openUpload() {
  const fallback = activeCode.value || ownCategories.value[0]?.code || ''
  Object.assign(form, { code: fallback, title: '', remark: '', fileName: '', raw: null })
  dialog.value = true
}

function onPick(file: UploadFile) {
  form.raw = (file.raw as File) || null
  form.fileName = file.name || ''
  if (!form.title.trim()) form.title = file.name || ''
}

async function submit() {
  if (!form.code) { ElMessage.warning('请选择文档分类'); return }
  if (!form.raw) { ElMessage.warning('请选择要上传的文件'); return }
  if (form.raw.size > MAX_ATTACHMENT_BYTES) { ElMessage.warning('单个文件不能超过 50MB'); return }
  const body = new FormData()
  body.append('ownerType', props.ownerType)
  body.append('ownerId', String(props.ownerId))
  body.append('category', form.code)
  if (form.title.trim()) body.append('title', form.title.trim())
  if (form.remark.trim()) body.append('remark', form.remark.trim())
  body.append('file', form.raw)
  uploading.value = true
  try {
    await upload<Attachment>('/attachments', body)
    ElMessage.success('文件已上传')
    dialog.value = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    uploading.value = false
  }
}

async function save(row: Attachment) {
  try {
    await downloadAttachment(row)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function remove(row: Attachment) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」？删除后不再显示在列表中。`, '删除文件', { type: 'warning' })  } catch { return }
  try {
    await api<void>('DELETE', `/attachments/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

const size = formatBytes

onMounted(() => {
  void load()
  if (props.showCategoryNav || props.canManage) void loadCategories()
})
</script>

<template>
  <div v-loading="loading" class="attachment-panel">
    <div class="attachment-head">
      <div>
        <h2>文档与文件</h2>
        <p class="muted">{{ hint || '按分类归档设计文档、过程记录和交付物；同名文件再次上传会保留历史版本。' }}</p>
      </div>
      <el-button v-if="canUpload" type="primary" @click="openUpload">上传文件</el-button>
    </div>

    <el-alert v-if="archived" title="项目已归档，文件只能查看和下载" type="warning" :closable="false" style="margin-bottom:14px" />

    <div class="attachment-body">
      <aside v-if="showCategoryNav" class="attachment-nav">
        <button class="doc-all" :class="{ active: !activeCode }" @click="activeCode = ''">全部文件（{{ files.length }}）</button>
        <div v-for="group in groups" :key="group" class="doc-group">
          <strong>{{ group }}</strong>
          <button v-for="item in groupCategories(group)" :key="item.code" class="doc-link" :class="{ active: activeCode === item.code }" @click="pickCategory(item.code)">
            {{ item.name }}
            <span class="doc-count">{{ files.filter(file => file.category === item.code).length }}</span>
          </button>
        </div>
      </aside>

      <div class="attachment-list">
        <p class="muted" style="margin:0 0 10px">{{ activeName }} · 共 {{ visibleFiles.length }} 个文件</p>
        <el-table :data="visibleFiles" empty-text="暂无文件">
          <el-table-column prop="title" label="标题" min-width="200">
            <template #default="scope">
              {{ scope.row.title }}
              <el-tag size="small" type="info" style="margin-left:6px">v{{ scope.row.version }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!showCategoryNav" prop="categoryName" label="分类" width="150" />
          <el-table-column prop="originalName" label="文件名" min-width="180" show-overflow-tooltip />
          <el-table-column label="大小" width="100"><template #default="scope">{{ size(scope.row.sizeBytes) }}</template></el-table-column>
          <el-table-column prop="uploaderName" label="上传人" width="110" />
          <el-table-column prop="createdAt" label="上传时间" width="170" />
          <el-table-column label="操作" width="160">
            <template #default="scope">
              <el-button v-if="previewKind(scope.row.originalName) !== 'none'" link type="primary" @click="openPreview(scope.row)">预览</el-button>
              <el-button link type="primary" @click="save(scope.row)">下载</el-button>
              <el-button v-if="canDeleteRow(scope.row)" link type="danger" @click="remove(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dialog" title="上传文件" width="560">
      <el-form label-position="top">
        <el-form-item label="文档分类" required>
          <el-select v-model="form.code" style="width:100%" filterable>
            <el-option-group v-for="group in groups" :key="group" :label="group">
              <el-option v-for="item in groupCategories(group)" :key="item.code" :label="item.name" :value="item.code" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" placeholder="默认使用文件名" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.remark" type="textarea" placeholder="版本说明、用途或注意事项" />
        </el-form-item>
        <el-form-item label="文件" required>
          <el-upload :auto-upload="false" :show-file-list="false" :on-change="onPick" drag style="width:100%">
            <div class="upload-hint">{{ form.fileName || '点击或拖拽文件到此处' }}</div>
            <div class="muted" style="font-size:12px">单个文件不超过 50MB，支持文档、图片、压缩包、流程图源文件</div>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submit">上传</el-button>
      </template>
    </el-dialog>

    <AttachmentPreview v-model="previewOpen" :attachment="previewItem" />
  </div>
</template>

<style scoped>
.attachment-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.attachment-head h2 { margin: 0 0 4px; }
.attachment-head p { margin: 0 0 14px; font-size: 13px; }
.attachment-body { display: flex; gap: 20px; align-items: flex-start; }
.attachment-nav { width: 210px; flex: none; max-height: 520px; overflow: auto; padding-right: 8px; border-right: 1px solid var(--line); }
.attachment-list { flex: 1; min-width: 0; }
.doc-all, .doc-link { display: flex; justify-content: space-between; width: 100%; border: 0; background: transparent; padding: 5px 8px; border-radius: 6px; cursor: pointer; font-size: 13px; color: var(--muted); text-align: left; }
.doc-all:hover, .doc-link:hover { background: rgba(0, 0, 0, 0.04); color: var(--navy); }
.doc-all.active, .doc-link.active { background: rgba(37, 99, 235, 0.1); color: var(--blue); font-weight: 600; }
.doc-group { margin-top: 10px; }
.doc-group strong { display: block; padding: 4px 8px; font-size: 12px; color: var(--muted); letter-spacing: 0.04em; }
.doc-count { font-size: 12px; opacity: 0.65; }
.upload-hint { font-size: 14px; color: var(--navy); }
</style>
