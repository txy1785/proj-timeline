<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { ModuleItem, NodeItem, User } from '../api/types'
import { isProjectManager, isWorkItemManager } from '../utils/permissions'
import { NODE_STATUS_LABELS } from '../utils/workItem'
import { useAuthStore } from '../stores/auth'

type CorrectionMode = 'reopen' | 'dates' | 'move'
// 时间线接口返回的模块不带 projectId
type CorrectionModule = Omit<ModuleItem, 'projectId'> & { projectId?: number | null }

const props = defineProps<{
  modelValue: boolean
  node: NodeItem
  members: User[]
  modules: CorrectionModule[]
  businessDate: string
  mode: CorrectionMode
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [node: NodeItem]; refresh: [] }>()

const REASON_MAX = 500
const PROJECT_TARGET = 'project'
const titles: Record<CorrectionMode, string> = { reopen: '重新打开工作项', dates: '修正实际日期', move: '移动工作项' }

const auth = useAuthStore()
const form = reactive({ reason: '', actualStart: '' as string | null, actualEnd: '' as string | null, target: '' })
const errors = reactive({ reason: '', actualStart: '', actualEnd: '', target: '' })
const saving = ref(false)
const submitted = ref(false)
const staleMessage = ref('')
const formError = ref('')
const expectedModuleId = ref<number | null>(null)
const refreshRequested = ref(false)

const currentUser = computed(() => auth.user)
const isPm = computed(() => isProjectManager(currentUser.value, props.members))

// 目标模块：PM 可移入任意模块或项目级；模块负责人只能移入自己负责的模块
const moveTargets = computed(() => {
  const currentModuleId = props.node.moduleId ?? null
  const options = props.modules
    .filter(module => (module.projectId == null || module.projectId === props.node.projectId) && module.id !== currentModuleId)
    .filter(module => isPm.value || module.ownerId === currentUser.value?.id)
    .map(module => ({ value: String(module.id), label: `${module.name}（负责人：${module.ownerName}）` }))
  if (isPm.value && currentModuleId !== null) options.unshift({ value: PROJECT_TARGET, label: '项目级（不归属模块）' })
  return options
})

const denial = computed(() => {
  if (props.node.projectStatus !== 'ACTIVE') return '项目未处于进行中，不能修正工作项'
  if (props.mode === 'move') return moveDenial()
  if (!isPm.value) return '只有项目经理可以执行此操作'
  if (props.mode === 'reopen' && props.node.status !== 'DONE') return '只有已完成的工作项可以重新打开'
  if (props.mode === 'dates' && !['DONE', 'IN_PROGRESS'].includes(props.node.status)) return '只有进行中或已完成的工作项可以修正实际日期'
  if (props.mode === 'dates' && !props.businessDate) return '业务日期尚未加载，暂时无法修正实际日期，请刷新页面后重试'
  return ''
})

function moveDenial(): string {
  if (!isWorkItemManager(currentUser.value, props.members, props.node)) return '只有项目经理、模块负责人或工作项负责人可以移动'
  if (!moveTargets.value.length) return '没有你可以移入的目标位置'
  return ''
}

const sourceLabel = computed(() => props.node.moduleId ? props.node.moduleName || `模块 #${props.node.moduleId}` : '项目级')
const targetLabel = computed(() => moveTargets.value.find(option => option.value === form.target)?.label ?? '')

const dirty = computed(() => {
  if (form.reason.trim() || form.target) return true
  if (props.mode !== 'dates') return false
  return (form.actualStart || '') !== (props.node.actualStart ?? '') || (form.actualEnd || '') !== initialActualEnd()
})

function initialActualEnd(): string {
  return props.node.status === 'IN_PROGRESS' ? '' : props.node.actualEnd ?? ''
}

function resetForm() {
  Object.assign(form, { reason: '', actualStart: props.node.actualStart ?? '', actualEnd: initialActualEnd(), target: '' })
  Object.assign(errors, { reason: '', actualStart: '', actualEnd: '', target: '' })
  submitted.value = false
  staleMessage.value = ''
  formError.value = ''
  expectedModuleId.value = props.node.moduleId ?? null
  refreshRequested.value = false
}

watch(() => props.modelValue, open => { if (open) resetForm() })
// 只有用户主动刷新后才采用新数据作为并发基线；保留已填写内容，由用户核对后再次提交
watch(() => props.node, node => {
  if (!refreshRequested.value) return
  refreshRequested.value = false
  expectedModuleId.value = node.moduleId ?? null
  staleMessage.value = ''
})
watch(form, () => { if (submitted.value) validate() }, { deep: true })

function refreshLatest() {
  refreshRequested.value = true
  emit('refresh')
}

function validate(): boolean {
  errors.reason = validateReason()
  errors.target = props.mode === 'move' ? validateTarget() : ''
  Object.assign(errors, props.mode === 'dates' ? validateDates() : { actualStart: '', actualEnd: '' })
  return !errors.reason && !errors.target && !errors.actualStart && !errors.actualEnd
}

function validateReason(): string {
  const reason = form.reason.trim()
  if (!reason) return '请填写原因'
  return reason.length > REASON_MAX ? `原因不能超过 ${REASON_MAX} 字` : ''
}

function validateTarget(): string {
  if (!form.target) return '请选择目标位置'
  return moveTargets.value.some(option => option.value === form.target) ? '' : '所选目标位置已不可用，请重新选择'
}

function validateDates() {
  const start = form.actualStart || ''
  const end = props.node.status === 'DONE' ? form.actualEnd || '' : ''
  const result = { actualStart: '', actualEnd: '' }
  if (props.node.status === 'IN_PROGRESS' && !start) result.actualStart = '进行中的工作项必须填写实际开始日期'
  if (props.node.status === 'DONE' && !end) result.actualEnd = '已完成的工作项必须填写实际完成日期'
  if (start > props.businessDate) result.actualStart = '实际开始日期不能晚于业务日期'
  if (end > props.businessDate) result.actualEnd = '实际完成日期不能晚于业务日期'
  if (start && end && start > end && !result.actualEnd) result.actualEnd = '实际完成日期不能早于实际开始日期'
  return result
}

function isFutureDate(date: Date): boolean {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}` > props.businessDate
}

async function submit() {
  if (saving.value) return
  submitted.value = true
  formError.value = ''
  if (denial.value || staleMessage.value || !validate()) return
  saving.value = true
  try {
    const saved = await sendRequest()
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (error) {
    handleError(error)
  } finally {
    saving.value = false
  }
}

function sendRequest(): Promise<NodeItem> {
  const reason = form.reason.trim()
  const path = `/nodes/${props.node.id}`
  if (props.mode === 'reopen') return api<NodeItem>('POST', `${path}/reopen`, { expectedStatus: 'DONE', reason })
  if (props.mode === 'dates') return api<NodeItem>('PUT', `${path}/actual-dates`, datesPayload(reason))
  const moduleId = form.target === PROJECT_TARGET ? null : Number(form.target)
  return api<NodeItem>('POST', `${path}/move`, { moduleId, expectedModuleId: expectedModuleId.value, reason })
}

function datesPayload(reason: string) {
  const actualEnd = props.node.status === 'IN_PROGRESS' ? null : form.actualEnd || null
  return { expectedStatus: props.node.status, actualStart: form.actualStart || null, actualEnd, reason }
}

function handleError(error: unknown) {
  const message = error instanceof Error ? error.message : '保存失败'
  if (error instanceof ApiError && error.status === 409) staleMessage.value = message || '工作项已被修改'
  else formError.value = message || '保存失败'
}

async function confirmDiscard(): Promise<boolean> {
  if (saving.value) return false
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('已填写的内容尚未保存，确定关闭吗？', '放弃修改', {
      type: 'warning', confirmButtonText: '放弃', cancelButtonText: '继续编辑'
    })
    return true
  } catch {
    return false
  }
}

async function requestClose() {
  if (await confirmDiscard()) emit('update:modelValue', false)
}

const guardRoute = () => !props.modelValue || confirmDiscard()
onBeforeRouteLeave(guardRoute)
onBeforeRouteUpdate(guardRoute)

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!props.modelValue || !(dirty.value || saving.value)) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', warnBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnBeforeUnload))
</script>

<template>
  <el-dialog
    :model-value="modelValue" :title="titles[mode]" width="min(520px, calc(100vw - 24px))" :close-on-click-modal="false"
    :close-on-press-escape="!saving" :show-close="!saving" :before-close="() => requestClose()">
    <div class="correction-summary">
      <strong>{{ node.name }}</strong>
      <span>当前状态：{{ NODE_STATUS_LABELS[node.status] }}</span>
    </div>
    <el-alert v-if="denial" class="correction-alert" type="warning" :title="denial" :closable="false" show-icon />
    <el-alert v-if="staleMessage" class="correction-alert" type="error" title="工作项已被他人修改" :closable="false" show-icon>
      <p>{{ staleMessage }}</p>
      <p>已填写的内容已保留，请刷新最新数据并核对后再提交。</p>
      <el-button size="small" :disabled="saving" @click="refreshLatest">刷新最新数据</el-button>
    </el-alert>
    <el-alert v-if="formError" class="correction-alert" type="error" :title="formError" :closable="false" show-icon />

    <el-form label-position="top" :disabled="saving || !!denial" @submit.prevent>
      <p v-if="mode === 'reopen'" class="correction-hint">将把已完成的工作项重新打开，以便继续处理。</p>

      <template v-else-if="mode === 'dates'">
        <el-form-item label="实际开始日期" :required="node.status === 'IN_PROGRESS'" :error="errors.actualStart">
          <el-date-picker
            v-model="form.actualStart" type="date" value-format="YYYY-MM-DD"
            :disabled-date="isFutureDate" placeholder="选择日期" clearable />
        </el-form-item>
        <el-form-item v-if="node.status === 'DONE'" label="实际完成日期" required :error="errors.actualEnd">
          <el-date-picker
            v-model="form.actualEnd" type="date" value-format="YYYY-MM-DD"
            :disabled-date="isFutureDate" placeholder="选择日期" clearable />
        </el-form-item>
        <p v-else class="correction-hint">进行中的工作项不填写实际完成日期。</p>
      </template>

      <template v-else>
        <el-form-item label="目标位置" required :error="errors.target">
          <el-select v-model="form.target" placeholder="选择目标位置" class="correction-select">
            <el-option v-for="option in moveTargets" :key="option.value" :value="option.value" :label="option.label" />
          </el-select>
        </el-form-item>
        <div class="correction-preview">
          <span>{{ sourceLabel }} → {{ targetLabel || '未选择' }}</span>
          <span>负责人保持不变：{{ node.ownerName || '未分配' }}</span>
        </div>
      </template>

      <el-form-item label="原因" required :error="errors.reason">
        <el-input
          v-model="form.reason" type="textarea" :rows="3" :maxlength="REASON_MAX" show-word-limit
          placeholder="说明本次修正的原因" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="saving" @click="requestClose">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!!denial || !!staleMessage" @click="submit">确认</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.correction-summary { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.correction-alert { margin-bottom: 12px; }
.correction-alert p { margin: 4px 0; }
.correction-hint { margin: 0 0 12px; color: var(--el-text-color-secondary); }
.correction-select { width: 100%; }
.correction-preview { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; padding: 8px 12px;
  border-radius: 4px; background: var(--el-fill-color-light); font-size: 13px; }
</style>
