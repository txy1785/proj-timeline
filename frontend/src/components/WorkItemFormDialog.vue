<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { ModuleItem, NodeItem, User, WorkItemType } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { useWorkItemDraft, workItemDraftKey } from '../composables/useWorkItemDraft'
import { isProjectManager, isWorkItemManager } from '../utils/permissions'
import { selectableMembers } from '../utils/member'
import { WORK_ITEM_TYPE_LABELS, WORK_ITEM_TYPES } from '../utils/workItem'

const props = defineProps<{
  modelValue: boolean; projectId: number; moduleId?: number | null; workItem?: NodeItem | null
  members: User[]; defaultOwnerId?: number; defaultPlanStart?: string; defaultPlanEnd?: string
  modules?: ModuleItem[]; canCreateProjectLevel?: boolean; businessDate?: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', value: NodeItem, options?: { continue?: boolean }): void
}>()
const auth = useAuthStore()
const app = useAppStore()
const saving = ref(false)
const advanced = ref<string[]>([])
const location = ref(-1)
const ownerTouched = ref(false)
const restoredOverEdit = ref(false)
const nameError = ref('')
const dateError = ref('')
const ownerError = ref('')
const actualStartError = ref('')
const actualEndError = ref('')
type HistoricalStatus = 'IN_PROGRESS' | 'DONE'
const HISTORICAL_STATUSES: HistoricalStatus[] = ['IN_PROGRESS', 'DONE']
const form = reactive({
  id: 0, name: '', description: '', type: 'FEATURE' as WorkItemType,
  planStart: null as string | null, planEnd: null as string | null, ownerId: null as number | null,
  historical: false, status: 'DONE' as HistoricalStatus, actualStart: null as string | null, actualEnd: null as string | null
})
watch(() => form.name, () => { nameError.value = '' })
watch(() => [form.planStart, form.planEnd], () => { dateError.value = '' })
watch(() => [form.historical, form.status, form.actualStart, form.actualEnd, form.ownerId], () => {
  actualStartError.value = ''
  actualEndError.value = ''
  ownerError.value = ''
})
/** 进行中的历史成果尚未完成，不能带实际完成日期。 */
watch(() => form.status, status => { if (status === 'IN_PROGRESS') form.actualEnd = null })
/** 交付要求只在新建时填写，创建后由「交付资料」页维护；空行不提交。 */
const MAX_REQUIREMENTS = 20
const requirements = ref<string[]>([])
const requirementLines = computed(() => requirements.value.map(line => line.trim()).filter(Boolean))
function addRequirement() { if (requirements.value.length < MAX_REQUIREMENTS) requirements.value.push('') }
function removeRequirement(index: number) { requirements.value = requirements.value.filter((_, position) => position !== index) }
type DraftSnapshot = { form: typeof form; location: number; requirements: string[] }
const isDateText = (value: unknown) => value === null || (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value))
function isDraftForm(value: unknown): value is DraftSnapshot['form'] {
  if (typeof value !== 'object' || value === null) return false
  const draft = value as Record<string, unknown>
  return typeof draft.id === 'number' && typeof draft.name === 'string' && typeof draft.description === 'string'
    && WORK_ITEM_TYPES.includes(draft.type as WorkItemType) && isDateText(draft.planStart) && isDateText(draft.planEnd)
    && (draft.ownerId === null || typeof draft.ownerId === 'number') && typeof draft.historical === 'boolean'
    && HISTORICAL_STATUSES.includes(draft.status as HistoricalStatus) && isDateText(draft.actualStart) && isDateText(draft.actualEnd)
}
function isDraftSnapshot(value: unknown): value is DraftSnapshot {
  if (typeof value !== 'object' || value === null) return false
  const draft = value as Record<string, unknown>
  return isDraftForm(draft.form) && typeof draft.location === 'number'
    && Array.isArray(draft.requirements) && draft.requirements.every(line => typeof line === 'string')
}
const {
  pending: pendingDraft, corrupt: draftCorrupt, dirty: draftDirty, storageFailed, start: startDraft, stop: stopDraft,
  persist: persistDraft, restore: takeDraft, discardPending, discardCorrupt, resetBaseline, clear: clearDraft,
  captureSubmitted, clearSubmitted
} = useWorkItemDraft<DraftSnapshot>(isDraftSnapshot)
/** 账号切换时递增，使在途保存的响应不再触碰当前界面。 */
let generation = 0
function snapshot(): DraftSnapshot { return { form: { ...form }, location: location.value, requirements: [...requirements.value] } }
/** 同步深度监听：每次输入立即落盘，刷新或崩溃时不丢最后一次修改。 */
watch([form, location, requirements], () => {
  if (props.modelValue) persistDraft(snapshot(), form.name.trim() || '未命名工作项')
}, { deep: true, flush: 'sync' })
const isPm = computed(() => isProjectManager(auth.user, props.members))
const modules = computed(() => (props.modules || []).filter(module => isPm.value || module.ownerId === auth.user?.id))
const projectLevelAllowed = computed(() => isPm.value && props.canCreateProjectLevel !== false)
const chooseLocation = computed(() => !form.id && props.modules !== undefined && props.moduleId === undefined)
const locationName = computed(() => props.workItem?.moduleName || props.modules?.find(module => module.id === location.value)?.name
  || (location.value === 0 ? '项目级工作项' : '当前模块'))
/** 历史成果只允许项目经理在新建时录入。 */
const historicalMode = computed(() => !form.id && isPm.value && form.historical)
/** 实际日期上限只取真实业务日期，未加载时不以本机日期冒充。 */
const businessDate = computed(() => props.businessDate || app.businessDate || '')
watch(historicalMode, enabled => {
  if (enabled && !props.businessDate) void loadBusinessDate()
})
/** ensure 内部吞掉请求错误（记入 app.error），故以是否取得业务日期判断成败；catch 防止未处理的拒绝。 */
async function loadBusinessDate() {
  try { await app.ensure() } catch { /* 统一由下方提示 */ }
  if (historicalMode.value && !businessDate.value) ElMessage.warning('业务日期加载失败，暂无法录入已有成果')
}

function isAfterBusinessDate(date: Date) {
  if (!businessDate.value) return false
  const text = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  return text > businessDate.value
}

function defaultOwner(moduleId: number) {
  if (moduleId > 0) return props.modules?.find(module => module.id === moduleId)?.ownerId || props.defaultOwnerId || null
  return props.members.find(member => member.projectRole === 'PM' && member.active && member.globalRole !== 'VIEWER')?.id || props.defaultOwnerId || null
}

/** 无模块上下文时：项目经理默认项目级，否则默认第一个有权限的模块。 */
function initialLocation(item?: NodeItem | null) {
  if (item) return item.moduleId || 0
  if (props.moduleId !== undefined) return props.moduleId || 0
  if (props.modules === undefined) return 0
  if (projectLevelAllowed.value) return 0
  return modules.value[0]?.id ?? -1
}

watch(() => props.modelValue, open => {
  if (!open) { stopDraft(); return }
  const item = props.workItem
  ownerTouched.value = false
  advanced.value = []
  requirements.value = ['']
  location.value = initialLocation(item)
  Object.assign(form, {
    id: item?.id || 0, name: item?.name || '', description: item?.description || '', type: item?.type || 'FEATURE',
    planStart: item?.planStart || null, planEnd: item?.planEnd || null,
    ownerId: item ? item.ownerId || null : location.value >= 0 ? defaultOwner(location.value) : props.defaultOwnerId || null,
    historical: false, status: 'DONE', actualStart: null, actualEnd: null
  })
  nameError.value = ''
  dateError.value = ''
  restoredOverEdit.value = false
  if (auth.user) startDraft(workItemDraftKey(auth.user.id, props.projectId, form.id), snapshot())
})

/** 账号切换时先停止草稿写入再关闭：旧输入只保留在原账号的键下，不写入新账号。 */
watch(() => auth.user?.id, (accountId, previousId) => {
  if (accountId === previousId) return
  generation++
  saving.value = false
  if (!props.modelValue) return
  stopDraft()
  resetForNext()
  close()
})

const draftSavedAt = computed(() => pendingDraft.value ? new Date(pendingDraft.value.savedAt).toLocaleString() : '')

function restoreDraft() {
  const draft = takeDraft()
  if (!draft) return
  Object.assign(form, { ...draft.form, id: form.id })
  if (chooseLocation.value) location.value = draft.location
  requirements.value = draft.requirements.length ? [...draft.requirements] : ['']
  ownerTouched.value = true
  restoredOverEdit.value = Boolean(form.id)
}

function close() { emit('update:modelValue', false) }

/** 返回 true 表示允许离开；暂存失败时没有可保留的草稿，常规关闭一律拦截，只能保存或点「放弃草稿」。 */
async function confirmLeave() {
  if (saving.value) return false
  if (!draftDirty.value) return true
  if (storageFailed.value) {
    ElMessage.warning('草稿暂存失败，关闭将丢失当前输入；请先保存，或点击「放弃草稿」明确放弃')
    return false
  }
  try {
    await ElMessageBox.confirm('当前修改已暂存为草稿，下次打开可恢复。', '尚未保存的修改', {
      confirmButtonText: '保留草稿并关闭', cancelButtonText: '继续编辑', distinguishCancelAndClose: true, type: 'warning'
    })
    return true
  } catch { return false }
}

async function requestClose() { if (await confirmLeave()) close() }
function beforeDialogClose(done: () => void) { void confirmLeave().then(allowed => { if (allowed) done() }) }

function discardAndClose() {
  if (saving.value) return
  clearDraft()
  close()
}

async function guardRoute() { return !props.modelValue || confirmLeave() }
onBeforeRouteLeave(guardRoute)
onBeforeRouteUpdate(guardRoute)

function guardUnload(event: BeforeUnloadEvent) {
  if (!props.modelValue || (!saving.value && !draftDirty.value)) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', guardUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', guardUnload))

function changeOwner(value: unknown) {
  form.ownerId = value ? Number(value) : null
  ownerTouched.value = true
}

async function changeLocation(value: number) {
  const ownerId = defaultOwner(value)
  if (!ownerId || ownerId === form.ownerId) return
  if (ownerTouched.value && form.ownerId) {
    try {
      await ElMessageBox.confirm('是否同时改用该位置的默认负责人？你也可以保留当前已选择的负责人。', '负责人默认值', {
        confirmButtonText: '改用默认负责人', cancelButtonText: '保留当前负责人'
      })
    } catch { return }
  }
  form.ownerId = ownerId
  ownerTouched.value = false
}

function validateHistorical() {
  if (!historicalMode.value) return true
  if (!businessDate.value) { ElMessage.warning('业务日期尚未加载，暂无法录入已有成果'); return false }
  const done = form.status === 'DONE'
  if (done && !form.actualEnd) actualEndError.value = '已完成的成果必须填写实际完成日期'
  else if (form.actualEnd && form.actualEnd > businessDate.value) actualEndError.value = `实际完成日期不能晚于业务日期 ${businessDate.value}`
  if (!done && !form.actualStart) actualStartError.value = '进行中的成果必须填写实际开始日期'
  else if (form.actualStart && form.actualStart > businessDate.value) actualStartError.value = `实际开始日期不能晚于业务日期 ${businessDate.value}`
  else if (form.actualStart && form.actualEnd && form.actualEnd < form.actualStart) actualEndError.value = '实际完成日期不能早于实际开始日期'
  if (!done && !form.ownerId) ownerError.value = '进行中的成果必须指定负责人'
  return !actualStartError.value && !actualEndError.value && !ownerError.value
}

function validate() {
  if (!form.name.trim()) { nameError.value = '请填写工作项名称'; return false }
  if (!validateHistorical()) return false
  if (form.planStart && form.planEnd && form.planEnd < form.planStart) {
    dateError.value = '计划结束日期不能早于开始日期'
    if (!advanced.value.includes('schedule')) advanced.value = [...advanced.value, 'schedule']
    return false
  }
  if (form.id) {
    if (!isWorkItemManager(auth.user, props.members, props.workItem) || props.workItem?.projectStatus === 'ARCHIVED') {
      ElMessage.warning('当前没有编辑该工作项的权限'); return false
    }
  } else if (chooseLocation.value) {
    if (location.value < 0 || (location.value === 0 && !projectLevelAllowed.value)
      || (location.value > 0 && !modules.value.some(module => module.id === location.value))) {
      ElMessage.warning('请选择有创建权限的所属位置'); return false
    }
  } else if (location.value !== initialLocation()) {
    ElMessage.warning('所属位置与当前上下文不一致，请重新打开表单'); return false
  } else if (location.value === 0 && !projectLevelAllowed.value) {
    ElMessage.warning('只有项目经理或管理员可以创建项目级工作项'); return false
  }
  return true
}

/** 继续创建时保留位置、负责人、类型和录入模式，便于连续录入同一模块的工作项。 */
function resetForNext() {
  Object.assign(form, { name: '', description: '', planStart: null, planEnd: null, actualStart: null, actualEnd: null })
  requirements.value = ['']
}

function historicalPayload() {
  if (!historicalMode.value) return {}
  return { historical: true, status: form.status, actualStart: form.actualStart || null,
    actualEnd: form.status === 'DONE' ? form.actualEnd : null }
}

async function save(continueNext = false) {
  if (saving.value || pendingDraft.value || draftCorrupt.value || !validate()) return
  const submittedGeneration = generation
  const submitted = captureSubmitted(snapshot())
  saving.value = true
  try {
    const payload = { name: form.name.trim(), description: form.description, type: form.type,
      planStart: form.planStart || null, planEnd: form.planEnd || null, ownerId: form.ownerId || null }
    const value = form.id
      ? await api<NodeItem>('PUT', `/nodes/${form.id}`, payload)
      : await api<NodeItem>('POST', `/projects/${props.projectId}/nodes`, { ...payload, ...historicalPayload(), moduleId: location.value > 0 ? location.value : undefined, requirements: requirementLines.value })
    clearSubmitted(submitted)
    if (submittedGeneration !== generation) return
    if (continueNext && !form.id) {
      ElMessage.success('工作项已创建，可继续新建下一项')
      resetForNext()
      resetBaseline(snapshot())
      emit('saved', value, { continue: true })
      return
    }
    clearDraft()
    emit('update:modelValue', false)
    ElMessage.success(form.id ? '工作项已更新' : '工作项已创建，可补充交付资料并开始工作')
    emit('saved', value)
  } catch (e) {
    if (submittedGeneration === generation) ElMessage.error((e as Error).message)
  } finally {
    if (submittedGeneration === generation) saving.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="form.id ? '编辑工作项' : '新建工作项'" width="min(620px, calc(100vw - 28px))" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" :before-close="beforeDialogClose" @update:model-value="emit('update:modelValue', $event)">
    <el-alert v-if="pendingDraft" type="warning" :closable="false" class="draft-alert" :title="`发现未保存的草稿「${pendingDraft.title}」`">
      <template #default>
        <p class="draft-time">暂存于 {{ draftSavedAt }}，请先选择恢复或放弃后再编辑。</p>
        <el-button size="small" type="primary" @click="restoreDraft">恢复草稿</el-button>
        <el-button size="small" @click="discardPending">放弃草稿</el-button>
      </template>
    </el-alert>
    <el-alert v-if="draftCorrupt" type="error" :closable="false" class="draft-alert" title="已暂存的草稿已损坏，无法恢复">
      <template #default>
        <p class="draft-time">请放弃该草稿后再编辑。</p>
        <el-button size="small" type="danger" @click="discardCorrupt">放弃损坏草稿</el-button>
      </template>
    </el-alert>
    <el-alert v-if="restoredOverEdit" type="warning" :closable="false" class="draft-alert" title="已恢复草稿，保存后可能覆盖服务器上他人的最新修改，请核对后再保存。" />
    <el-form label-position="top" :disabled="saving || Boolean(pendingDraft) || draftCorrupt" @submit.prevent="save()">
      <el-form-item label="工作项名称" required :error="nameError"><el-input v-model="form.name" maxlength="200" placeholder="例如：支付渠道接入" /></el-form-item>
      <el-form-item v-if="!form.id && isPm">
        <el-switch v-model="form.historical" active-text="录入已有成果" />
      </el-form-item>
      <template v-if="historicalMode">
        <el-form-item label="当前状态" required>
          <el-radio-group v-model="form.status">
            <el-radio-button value="DONE">已完成</el-radio-button>
            <el-radio-button value="IN_PROGRESS">进行中</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="实际开始日期" :required="form.status === 'IN_PROGRESS'" :error="actualStartError">
            <el-date-picker v-model="form.actualStart" value-format="YYYY-MM-DD" :disabled-date="isAfterBusinessDate" placeholder="选择日期" style="width:100%" />
          </el-form-item>
          <el-form-item v-if="form.status === 'DONE'" label="实际完成日期" required :error="actualEndError">
            <el-date-picker v-model="form.actualEnd" value-format="YYYY-MM-DD" :disabled-date="isAfterBusinessDate" placeholder="选择日期" style="width:100%" />
          </el-form-item>
        </div>
        <small class="field-hint historical-hint">实际完成日期用于周报归周；原计划日期未填写时保持未知，不会用实际日期补齐。</small>
      </template>
      <el-form-item label="所属位置">
        <el-select v-if="chooseLocation" :model-value="location < 0 ? undefined : location" placeholder="选择功能模块或项目级位置" style="width:100%" @update:model-value="location = Number($event)" @change="changeLocation">
          <el-option v-for="module in modules" :key="module.id" :label="module.name" :value="module.id" />
          <el-option v-if="projectLevelAllowed" label="项目级工作项" :value="0" />
        </el-select>
        <el-input v-else :model-value="locationName" disabled />
        <small class="field-hint">{{ form.id ? '已有工作项的所属位置保持不变。' : '功能工作选择功能模块；跨模块测试、维护和上线保障选择项目级工作项。' }}</small>
      </el-form-item>
      <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="补充交付范围或验收说明（可选）" /></el-form-item>
      <el-form-item label="负责人" :required="historicalMode && form.status === 'IN_PROGRESS'" :error="ownerError">
        <el-select :model-value="form.ownerId || undefined" style="width:100%" filterable clearable placeholder="暂不指定" @update:model-value="changeOwner">
          <el-option v-for="member in selectableMembers(members)" :key="member.id" :label="member.name" :value="member.id" />
        </el-select>
      </el-form-item>
      <el-collapse v-model="advanced">
        <el-collapse-item title="安排（计划日期、类型，可选）" name="schedule">
          <div class="form-grid">
            <el-form-item label="计划开始日期"><el-date-picker v-model="form.planStart" value-format="YYYY-MM-DD" placeholder="暂不确定" style="width:100%" /></el-form-item>
            <el-form-item label="计划结束日期" :error="dateError"><el-date-picker v-model="form.planEnd" value-format="YYYY-MM-DD" placeholder="暂不确定" style="width:100%" /></el-form-item>
          </div>
          <el-form-item label="类型"><el-select v-model="form.type" style="width:100%"><el-option v-for="type in WORK_ITEM_TYPES" :key="type" :label="WORK_ITEM_TYPE_LABELS[type]" :value="type" /></el-select></el-form-item>
        </el-collapse-item>
        <el-collapse-item v-if="!form.id" title="交付要求（可选）" name="requirements">
          <el-form-item>
            <div class="requirement-rows">
              <div v-for="index in requirements.length" :key="index" class="requirement-row">
                <el-input v-model="requirements[index - 1]" type="textarea" :rows="2" maxlength="200" placeholder="例如：接口文档 v1、联调记录、测试报告（空行不提交）" />
                <el-button link type="danger" :disabled="saving" @click="removeRequirement(index - 1)">删除</el-button>
              </div>
            </div>
            <el-button link type="primary" :disabled="saving || requirements.length >= MAX_REQUIREMENTS" @click="addRequirement">添加一条</el-button>
          </el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="requestClose">取消</el-button>
      <el-button v-if="draftDirty" type="danger" plain :disabled="saving" @click="discardAndClose">放弃草稿</el-button>
      <el-button v-if="!form.id" :loading="saving" :disabled="Boolean(pendingDraft) || draftCorrupt" @click="save(true)">保存并继续</el-button>
      <el-button type="primary" :loading="saving" :disabled="Boolean(pendingDraft) || draftCorrupt" @click="save()">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint{display:block;width:100%;margin-top:6px;line-height:1.6;color:var(--muted)}
.draft-alert{margin-bottom:16px}
.draft-time{margin:0 0 8px}
.historical-hint{margin:-8px 0 18px}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.requirement-rows{display:grid;gap:8px;width:100%}
.requirement-row{display:flex;align-items:flex-start;gap:8px}
.requirement-row>.el-button{flex:none}
@media(max-width:600px){.form-grid{grid-template-columns:1fr;gap:0}}
</style>
