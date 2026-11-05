<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, upload } from '../api/client'
import type { Attachment, AttachmentCategory } from '../api/types'
import { fetchAttachmentBlob } from '../utils/attachment'
import { useAuthStore } from '../stores/auth'
import MarkdownEditor from './MarkdownEditor.vue'

type DocMode = 'new' | 'edit' | 'link'
interface DocForm { title: string; category: string; body: string; link: string }

const props = defineProps<{ modelValue: boolean; projectId: number; mode: DocMode; attachment?: Attachment | null; canManage: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [attachment: Attachment] }>()

const DEFAULT_CATEGORY = 'PROJECT_PLAN'
const HTTP_LINK = /^https?:\/\/[^\s<>"`]+$/i
// 仓库内相对路径：禁止绝对路径、盘符/协议（冒号）、`..` 越级段与空白
const REPO_PATH = /^(?![\\/])(?!.*(?:^|[\\/])\.\.(?:[\\/]|$))[^\s:`<>"|?*]+$/
const UNSAFE_FILE_CHARS = /[\\/:*?"<>|]/g
const auth = useAuthStore()
const form = ref<DocForm>(emptyForm())
const baseline = ref('')
const categories = ref<AttachmentCategory[]>([])
const loadingBody = ref(false)
const loadError = ref('')
const saving = ref(false)
const pendingDraft = ref<DocForm | null>(null)
// 草稿无法读取或已损坏时保留原始存储内容，直到用户明确丢弃
const draftProblem = ref('')
const storageError = ref('')
let loadToken = 0
// 草稿键在打开时固定，避免账号/props 变化后把内容写进别人的键
let activeKey = ''
let initialized = false

const isEdit = computed(() => props.mode === 'edit')
const isLink = computed(() => props.mode === 'link')
const dialogTitle = computed(() => isEdit.value ? `编辑文档：${props.attachment?.title || ''}` : isLink.value ? '添加链接' : '新建文档')
const dirty = computed(() => props.modelValue && JSON.stringify(form.value) !== baseline.value)
const locked = computed(() => !props.canManage || saving.value || !!pendingDraft.value || !!draftProblem.value)
function buildDraftKey(): string {
  return `timeline-doc-draft:${auth.user?.id ?? 0}:${props.projectId}:${isEdit.value ? `edit-${props.attachment?.id}` : props.mode}`
}

function captureSession() {
  return { token: loadToken, key: activeKey, userId: auth.user?.id }
}

// 异步响应返回时若已关闭/重开或切换账号，则不得再触碰当前界面与草稿
function isCurrentSession(session: ReturnType<typeof captureSession>): boolean {
  return session.token === loadToken && session.key === activeKey && session.userId === auth.user?.id
}

function emptyForm(): DocForm {
  return { title: '', category: DEFAULT_CATEGORY, body: '', link: '' }
}

function resetBaseline() {
  baseline.value = JSON.stringify(form.value)
}

function isDocForm(value: unknown): value is DocForm {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return (['title', 'category', 'body', 'link'] as const).every(field => typeof record[field] === 'string')
}

function readDraft(): DocForm | null {
  if (!activeKey) return null
  let raw: string | null
  try {
    raw = sessionStorage.getItem(activeKey)
  } catch (error) {
    draftProblem.value = `无法读取暂存草稿（${(error as Error).message}）`
    return null
  }
  if (!raw) return null
  try {
    const data: unknown = JSON.parse(raw)
    if (isDocForm(data)) return data
  } catch { /* 解析失败与结构不符统一按损坏处理 */ }
  draftProblem.value = '暂存草稿已损坏，无法恢复'
  return null
}

function clearDraft() {
  if (!activeKey) return
  try { sessionStorage.removeItem(activeKey) } catch { /* 清理失败不阻断关闭/保存流程 */ }
}

function persistDraft(): boolean {
  if (!initialized || !props.modelValue || loadingBody.value || pendingDraft.value || draftProblem.value || !activeKey) return true
  try {
    if (dirty.value) sessionStorage.setItem(activeKey, JSON.stringify(form.value))
    else sessionStorage.removeItem(activeKey)
    storageError.value = ''
    return true
  } catch (error) {
    storageError.value = `草稿无法暂存到浏览器（${(error as Error).message}），请及时保存`
    return false
  }
}

function editTargetProblem(attachment: Attachment): string {
  if (attachment.ownerType !== 'PROJECT' || Number(attachment.ownerId) !== props.projectId) return '该文档不属于当前项目'
  if (!/\.md$/i.test(attachment.originalName)) return '仅支持编辑 Markdown 文档'
  return ''
}

async function loadCategories() {
  const session = captureSession()
  if (!categories.value.length) {
    try {
      const all = await api<AttachmentCategory[]>('GET', '/attachments/categories')
      if (!isCurrentSession(session)) return
      categories.value = all.filter(item => item.ownerType === 'PROJECT')
    } catch (error) {
      if (isCurrentSession(session)) ElMessage.error((error as Error).message)
      return
    }
  }
  if (isEdit.value || !categories.value.length || categories.value.some(item => item.code === form.value.category)) return
  const wasDirty = dirty.value
  form.value.category = categories.value[0].code
  if (!wasDirty) resetBaseline()
}

async function loadBody(token: number) {
  const attachment = props.attachment
  if (!attachment) { loadError.value = '未指定要编辑的文档'; return }
  const problem = editTargetProblem(attachment)
  if (problem) { loadError.value = problem; return }
  loadingBody.value = true
  try {
    const text = await (await fetchAttachmentBlob(attachment)).text()
    if (token !== loadToken) return
    form.value.body = text
    resetBaseline()
  } catch (error) {
    if (token === loadToken) loadError.value = (error as Error).message
  } finally {
    if (token === loadToken) loadingBody.value = false
  }
}

// 草稿必须在表单重置/加载前读出，否则会被新的空表单写回覆盖
async function open() {
  const token = ++loadToken
  initialized = false
  activeKey = buildDraftKey()
  draftProblem.value = ''
  const draft = readDraft()
  loadError.value = ''
  storageError.value = ''
  pendingDraft.value = null
  const attachment = props.attachment
  form.value = isEdit.value && attachment ? { ...emptyForm(), title: attachment.title, category: attachment.category } : emptyForm()
  resetBaseline()
  if (isEdit.value) await loadBody(token)
  else void loadCategories()
  if (token !== loadToken) return
  if (!loadError.value) pendingDraft.value = draft
  initialized = true
}

// 先清 pending 再赋值，让同步持久化立即写入恢复后的内容
function restoreDraft() {
  const draft = pendingDraft.value
  if (!draft) return
  pendingDraft.value = null
  form.value = isEdit.value
    ? { ...form.value, body: draft.body }
    : { title: draft.title, category: draft.category, body: draft.body, link: draft.link }
}

function discardDraft() {
  clearDraft()
  pendingDraft.value = null
  draftProblem.value = ''
}

async function confirmLeave(): Promise<boolean> {
  if (saving.value) { ElMessage.warning('正在保存，请稍候'); return false }
  if (!dirty.value) return true
  if (!persistDraft()) { ElMessage.warning('草稿无法暂存，请先保存，或点击“放弃修改”后再离开'); return false }
  try {
    await ElMessageBox.confirm('当前修改尚未保存，草稿已暂存在本标签页，下次打开可恢复。', '关闭编辑？', {
      type: 'warning', confirmButtonText: '保留草稿并关闭', cancelButtonText: '继续编辑'
    })
  } catch { return false }
  return true
}

async function requestClose() {
  if (await confirmLeave()) emit('update:modelValue', false)
}

async function discardChanges() {
  if (saving.value) return
  try {
    await ElMessageBox.confirm('将删除当前修改与暂存草稿，且无法恢复。', '放弃修改？', {
      type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑'
    })
  } catch { return }
  initialized = false
  clearDraft()
  resetBaseline()
  emit('update:modelValue', false)
}

function resetForAccountChange() {
  loadToken++
  initialized = false
  activeKey = ''
  form.value = emptyForm()
  resetBaseline()
  pendingDraft.value = null
  draftProblem.value = ''
  categories.value = []
  loadError.value = ''
  storageError.value = ''
  loadingBody.value = false
  saving.value = false
  if (props.modelValue) emit('update:modelValue', false)
}

function escapeLabel(value: string) {
  return value.replace(/[[\]\\]/g, ch => `\\${ch}`)
}

function linkLine(): string {
  const target = form.value.link.trim()
  if (HTTP_LINK.test(target)) return `[${escapeLabel(form.value.title.trim())}](<${target}>)`
  if (REPO_PATH.test(target)) return `\`${target}\``
  return ''
}

function safeFileName(title: string): string {
  const printable = Array.from(title).filter(ch => ch >= ' ').join('')
  const base = printable.replace(/\.md$/i, '').replace(UNSAFE_FILE_CHARS, '_')
    .replace(/\s+/g, ' ').replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 80)
  return `${base || '项目文档'}.md`
}

function validate(): string {
  if (!props.canManage) return '当前没有编辑项目文档的权限'
  if (pendingDraft.value) return '请先选择恢复或丢弃草稿'
  if (draftProblem.value) return '请先处理无法恢复的暂存草稿'
  if (loadingBody.value) return '文档内容仍在加载'
  if (loadError.value) return '原文档加载失败，无法保存'
  if (isEdit.value && (!props.attachment || editTargetProblem(props.attachment))) return '编辑目标无效，无法保存'
  if (!form.value.title.trim()) return '请填写文档标题'
  if (!form.value.category) return '请选择文档分类'
  if (isLink.value && !linkLine()) return '请输入 http(s) 链接或仓库内相对路径'
  if (!isLink.value && !form.value.body.trim()) return '文档内容不能为空'
  return ''
}

// 编辑时沿用原标题与原文件名，后端据此生成新版本，旧版本保持不变
function buildPayload(): FormData {
  const attachment = isEdit.value ? props.attachment : null
  const title = attachment ? attachment.title : form.value.title.trim()
  const content = isLink.value ? `# ${title}\n\n${linkLine()}\n` : form.value.body
  const fileName = attachment ? attachment.originalName : safeFileName(title)
  const body = new FormData()
  body.append('ownerType', 'PROJECT')
  body.append('ownerId', String(props.projectId))
  body.append('category', attachment ? attachment.category : form.value.category)
  body.append('title', title)
  body.append('file', new File([content], fileName, { type: 'text/markdown' }))
  return body
}

// 只删除仍等于提交内容的草稿：响应过期也要清理原键，但提交后的新编辑不能被误删
function clearSubmittedDraft(key: string, submittedForm: string) {
  if (!key) return
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return
    const data: unknown = JSON.parse(raw)
    if (isDocForm(data) && JSON.stringify(data) === submittedForm) sessionStorage.removeItem(key)
  } catch { /* 清理失败只留下可手动丢弃的旧草稿，不影响已保存结果 */ }
}

async function submit() {
  if (saving.value) return
  const problem = validate()
  if (problem) { ElMessage.warning(problem); return }
  const session = captureSession()
  const submittedKey = activeKey
  const submittedForm = JSON.stringify(form.value)
  saving.value = true
  try {
    const saved = await upload<Attachment>('/attachments', buildPayload())
    clearSubmittedDraft(submittedKey, submittedForm)
    if (!isCurrentSession(session)) return
    clearDraft()
    resetBaseline()
    saving.value = false
    ElMessage.success(isEdit.value ? `已保存为新版本 v${saved.version}` : '文档已保存')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    if (isCurrentSession(session)) ElMessage.error(`保存失败：${(error as Error).message}，已保留当前内容`)
  } finally {
    if (isCurrentSession(session)) saving.value = false
  }
}

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !saving.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(form, () => { persistDraft() }, { deep: true, flush: 'sync' })
watch(() => props.modelValue, value => {
  if (value) { void open(); return }
  loadToken++
  initialized = false
  saving.value = false
}, { immediate: true })
watch(() => auth.user?.id, (next, prev) => { if (next !== prev) resetForAccountChange() })
onBeforeRouteLeave(() => confirmLeave())
onBeforeRouteUpdate(() => confirmLeave())
onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))
</script>

<template>
  <el-dialog :model-value="modelValue" :title="dialogTitle" width="min(860px, calc(100vw - 24px))" append-to-body destroy-on-close :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="requestClose">
    <el-alert v-if="pendingDraft" type="info" title="发现上次未保存的草稿" :closable="false" class="doc-alert">
      <el-button link type="primary" @click="restoreDraft">恢复草稿</el-button>
      <el-button link @click="discardDraft">丢弃草稿</el-button>
    </el-alert>
    <el-alert v-if="draftProblem" type="warning" :title="`${draftProblem}，原始内容仍保留在本标签页`" :closable="false" class="doc-alert">
      <el-button link type="danger" @click="discardDraft">丢弃草稿</el-button>
    </el-alert>
    <el-alert v-if="loadError" type="error" :title="`原文档加载失败：${loadError}`" :closable="false" class="doc-alert" />
    <el-alert v-if="storageError" type="warning" :title="storageError" :closable="false" class="doc-alert" />
    <el-form label-position="top" @submit.prevent>
      <div class="doc-meta">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" :disabled="isEdit || locked" maxlength="100" placeholder="例如：项目实施计划" />
        </el-form-item>
        <el-form-item label="分类" required>
          <el-input v-if="isEdit" :model-value="attachment?.categoryName || attachment?.category" disabled />
          <el-select v-else v-model="form.category" filterable :disabled="locked" style="width:100%">
            <el-option v-for="item in categories" :key="item.code" :label="item.name" :value="item.code" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item v-if="isLink" label="链接或仓库路径" required>
        <el-input v-model="form.link" :disabled="locked" placeholder="https://… 或 docs/design/plan.md" />
        <p class="muted doc-tip">只保存为文本记录，系统不会访问该链接。</p>
      </el-form-item>
      <el-form-item v-else label="正文" required>
        <div v-loading="loadingBody" class="doc-editor">
          <MarkdownEditor v-model="form.body" owner-type="PROJECT" :owner-id="projectId" :can-upload="false" :disabled="locked || loadingBody || !!loadError" aria-label="文档正文" />
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <span v-if="isEdit" class="muted doc-tip doc-version">保存后生成新版本，旧版本保留</span>
      <el-button v-if="dirty" type="danger" plain :disabled="saving" @click="discardChanges">放弃修改</el-button>
      <el-button :disabled="saving" @click="requestClose">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="locked" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.doc-alert { margin-bottom: 14px; }
.doc-meta { display: grid; grid-template-columns: 2fr 1fr; gap: 16px; }
.doc-editor { width: 100%; }
.doc-tip { margin: 6px 0 0; font-size: 12px; line-height: 1.6; }
.doc-version { margin-right: 12px; }
@media (max-width: 700px) { .doc-meta { grid-template-columns: 1fr; } }
</style>
