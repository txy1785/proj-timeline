<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { ModuleItem, NodeItem, User, WorkItemType } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useAppStore } from '../stores/app'
import { isProjectManager, isWorkItemManager } from '../utils/permissions'
import { selectableMembers } from '../utils/member'
import { WORK_ITEM_TYPE_LABELS, WORK_ITEM_TYPES } from '../utils/workItem'

const props = defineProps<{
  modelValue: boolean; projectId: number; moduleId?: number | null; workItem?: NodeItem | null
  members: User[]; defaultOwnerId?: number; defaultPlanStart?: string; defaultPlanEnd?: string
  modules?: ModuleItem[]; canCreateProjectLevel?: boolean; businessDate?: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'saved', value: NodeItem): void }>()
const auth = useAuthStore()
const app = useAppStore()
const saving = ref(false)
const initializing = ref(false)
const advanced = ref('')
const location = ref(-1)
const ownerTouched = ref(false)
let generation = 0
const form = reactive({ id: 0, name: '', description: '', type: 'FEATURE' as WorkItemType, planStart: '', planEnd: '', ownerId: 0 })
const isPm = computed(() => isProjectManager(auth.user, props.members))
const modules = computed(() => (props.modules || []).filter(module => isPm.value || module.ownerId === auth.user?.id))
const projectLevelAllowed = computed(() => isPm.value && props.canCreateProjectLevel !== false)
const chooseLocation = computed(() => !form.id && props.modules !== undefined && props.moduleId === undefined)
const locationName = computed(() => props.workItem?.moduleName || props.modules?.find(module => module.id === location.value)?.name
  || (location.value === 0 ? '项目级工作项' : '当前模块'))

function defaultOwner(moduleId: number) {
  if (moduleId > 0) return props.modules?.find(module => module.id === moduleId)?.ownerId || props.defaultOwnerId || 0
  return props.members.find(member => member.projectRole === 'PM' && member.active && member.globalRole !== 'VIEWER')?.id || props.defaultOwnerId || 0
}

watch(() => props.modelValue, async open => {
  const current = ++generation
  initializing.value = false
  if (!open) return
  const item = props.workItem
  ownerTouched.value = false
  advanced.value = ''
  location.value = item ? item.moduleId || 0 : props.moduleId !== undefined ? props.moduleId || 0 : props.modules !== undefined ? -1 : 0
  Object.assign(form, {
    id: item?.id || 0, name: item?.name || '', description: item?.description || '', type: item?.type || 'FEATURE',
    planStart: item?.planStart || '', planEnd: item?.planEnd || '',
    ownerId: item?.ownerId || (location.value >= 0 ? defaultOwner(location.value) : props.defaultOwnerId || 0)
  })
  if (item) return
  initializing.value = true
  await app.ensure()
  if (current !== generation || !props.modelValue) return
  const today = props.businessDate || app.businessDate
  form.planStart = [today, props.defaultPlanStart || ''].sort().at(-1) || ''
  initializing.value = false
})

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

async function save() {
  if (saving.value || initializing.value) return
  if (!form.name.trim() || !form.planStart || !form.planEnd || !form.ownerId) {
    ElMessage.warning('请填写工作项名称、负责人和计划起止日期'); return
  }
  if (form.planEnd < form.planStart) { ElMessage.warning('计划结束日期不能早于开始日期'); return }
  if (form.id) {
    if (!isWorkItemManager(auth.user, props.members, props.workItem) || props.workItem?.projectStatus === 'ARCHIVED') {
      ElMessage.warning('当前没有编辑该工作项的权限'); return
    }
  } else if (chooseLocation.value) {
    if (location.value < 0 || (location.value === 0 && !projectLevelAllowed.value)
      || (location.value > 0 && !modules.value.some(module => module.id === location.value))) {
      ElMessage.warning('请选择有创建权限的所属位置'); return
    }
  } else if (location.value === 0 && !projectLevelAllowed.value) {
    ElMessage.warning('只有项目经理或管理员可以创建项目级工作项'); return
  }
  saving.value = true
  try {
    const payload = { name: form.name.trim(), description: form.description, type: form.type,
      planStart: form.planStart, planEnd: form.planEnd, ownerId: form.ownerId }
    const value = form.id
      ? await api<NodeItem>('PUT', `/nodes/${form.id}`, payload)
      : await api<NodeItem>('POST', `/projects/${props.projectId}/nodes`, { ...payload, moduleId: location.value > 0 ? location.value : undefined })
    emit('update:modelValue', false)
    ElMessage.success(form.id ? '工作项已更新' : '工作项已创建，可补充交付资料并开始工作')
    emit('saved', value)
  } catch (e) { ElMessage.error((e as Error).message) } finally { saving.value = false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="form.id ? '编辑工作项' : '新建工作项'" width="min(620px, calc(100vw - 28px))" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @update:model-value="emit('update:modelValue', $event)">
    <p class="form-intro">明确一次交付、负责人和计划日期。</p>
    <el-form label-position="top" :disabled="saving || initializing" @submit.prevent="save">
      <el-form-item label="工作项名称" required><el-input v-model="form.name" maxlength="200" placeholder="例如：支付渠道接入" /></el-form-item>
      <el-form-item label="所属位置" required>
        <el-select v-if="chooseLocation" :model-value="location < 0 ? undefined : location" placeholder="选择功能模块或项目级位置" style="width:100%" @update:model-value="location = Number($event)" @change="changeLocation">
          <el-option v-for="module in modules" :key="module.id" :label="module.name" :value="module.id" />
          <el-option v-if="projectLevelAllowed" label="项目级工作项" :value="0" />
        </el-select>
        <el-input v-else :model-value="locationName" disabled />
        <small class="field-hint">{{ form.id ? '已有工作项的所属位置保持不变。' : '功能工作选择功能模块；跨模块测试、维护和上线保障选择项目级工作项。' }}</small>
      </el-form-item>
      <el-form-item label="负责人" required>
        <el-select :model-value="form.ownerId || undefined" style="width:100%" filterable placeholder="选择负责人" @update:model-value="form.ownerId = Number($event)" @change="ownerTouched = true">
          <el-option v-for="member in selectableMembers(members)" :key="member.id" :label="member.name" :value="member.id" />
        </el-select>
        <small class="field-hint">默认带入模块负责人；项目级工作项默认由项目经理负责。</small>
      </el-form-item>
      <div class="form-grid">
        <el-form-item label="计划开始日期" required><el-date-picker v-model="form.planStart" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="计划结束日期" required><el-date-picker v-model="form.planEnd" value-format="YYYY-MM-DD" placeholder="明确本次交付日期" style="width:100%" /></el-form-item>
      </div>
      <el-collapse v-model="advanced" accordion>
        <el-collapse-item title="更多信息（类型、说明）" name="more">
          <el-form-item label="类型"><el-select v-model="form.type" style="width:100%"><el-option v-for="type in WORK_ITEM_TYPES" :key="type" :label="WORK_ITEM_TYPE_LABELS[type]" :value="type" /></el-select></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="补充交付范围或验收说明（可选）" /></el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <template #footer><el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="saving" :disabled="initializing" @click="save">{{ form.id ? '保存修改' : '创建并查看详情' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.form-intro{margin:0 0 20px;color:var(--muted)}
.field-hint{display:block;width:100%;margin-top:6px;line-height:1.6;color:var(--muted)}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
@media(max-width:600px){.form-grid{grid-template-columns:1fr;gap:0}}
</style>
