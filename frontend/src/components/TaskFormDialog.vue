<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, Task, TaskKind, User } from '../api/types'
import { selectableMembers } from '../utils/member'
import { TASK_KIND_LABELS, TASK_KINDS } from '../utils/task'

const props = withDefaults(defineProps<{
  modelValue: boolean
  nodeId: number
  task?: Task | null
  members: User[]
  defaultOwnerId?: number
  workItems?: NodeItem[]
}>(), { task: null, defaultOwnerId: 0, workItems: () => [] })
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'saved', task: Task): void }>()
const form = reactive({
  name: '', assigneeId: 0, dueDate: '', description: '', kind: 'FEATURE' as TaskKind,
  relatedNodeId: undefined as number | undefined, situation: '', handling: '',
  estimateDays: undefined as number | undefined
})
const saving = ref(false)
const expanded = ref<string[]>([])
const baseline = ref('')
const options = computed(() => selectableMembers(props.members))
const unavailableAssignee = computed(() => form.assigneeId > 0 && !options.value.some(member => member.id === form.assigneeId))
const originalRelatedMissing = computed(() => props.task?.relatedNodeId && !props.workItems.some(item => item.id === props.task?.relatedNodeId))
const dirty = computed(() => JSON.stringify(form) !== baseline.value)

watch(() => props.modelValue, open => {
  if (!open) return
  const task = props.task
  Object.assign(form, {
    name: task?.name || '', assigneeId: task?.assigneeId || props.defaultOwnerId,
    dueDate: task?.dueDate || '', description: task?.description || '', kind: task?.kind || 'FEATURE',
    relatedNodeId: task?.relatedNodeId ?? undefined, situation: task?.situation || '',
    handling: task?.handling || '', estimateDays: task?.estimateDays ?? undefined
  })
  expanded.value = []
  baseline.value = JSON.stringify(form)
}, { immediate: true })

async function allowClose(): Promise<boolean> {
  if (saving.value) return false
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('任务信息尚未保存。继续编辑可先保存，或放弃本次修改后关闭。', '未保存的任务', {
        type: 'warning', confirmButtonText: '放弃并关闭', cancelButtonText: '继续编辑'
      })
    } catch { return false }
  }
  return true
}
async function requestClose(done?: () => void) {
  if (!await allowClose()) return
  if (done) done()
  else emit('update:modelValue', false)
}
onBeforeRouteLeave(async () => !props.modelValue || await allowClose())
function beforeUnload(event: BeforeUnloadEvent) {
  if (props.modelValue && dirty.value) { event.preventDefault(); event.returnValue = '' }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

async function save() {
  if (saving.value) return
  if (!form.name.trim()) { ElMessage.warning('请填写任务名称'); return }
  if (!options.value.some(member => member.id === form.assigneeId)) {
    ElMessage.warning('请选择项目内有效且非只读的执行人'); return
  }
  const payload = {
    name: form.name.trim(), assigneeId: form.assigneeId, dueDate: form.dueDate || null,
    description: form.description, kind: form.kind, relatedNodeId: form.relatedNodeId ?? null,
    situation: form.situation, handling: form.handling, estimateDays: form.estimateDays ?? null
  }
  saving.value = true
  try {
    const task = props.task
      ? await api<Task>('PUT', `/tasks/${props.task.id}`, payload)
      : await api<Task>('POST', `/nodes/${props.nodeId}/tasks`, payload)
    baseline.value = JSON.stringify(form)
    emit('update:modelValue', false)
    emit('saved', task)
    ElMessage.success(props.task ? '任务已更新' : '任务已创建')
  } catch (error) { ElMessage.error((error as Error).message) }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="task ? '编辑任务' : '新建任务'" width="min(600px, calc(100vw - 28px))" :before-close="requestClose" :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <p class="muted task-form-context">任务归属于当前工作项；补充信息可以稍后完善。</p>
    <el-form label-position="top" :disabled="saving" @submit.prevent="save">
      <el-form-item label="任务名称" required>
        <el-input v-model="form.name" maxlength="200" placeholder="例如：验证库存不足分支" />
      </el-form-item>
      <div class="task-form-grid">
        <el-form-item label="执行人">
          <el-select v-model="form.assigneeId" filterable style="width:100%">
            <el-option v-if="unavailableAssignee" :value="form.assigneeId" :label="`${task?.assigneeName || '原执行人'}（已不可指派）`" disabled />
            <el-option v-for="member in options" :key="member.id" :value="member.id" :label="member.name" />
          </el-select>
          <small v-if="!task" class="muted">默认继承工作项负责人</small>
        </el-form-item>
        <el-form-item label="截止日期（可选）">
          <el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" clearable placeholder="选择日期" style="width:100%" />
        </el-form-item>
      </div>
      <el-collapse v-model="expanded" class="task-more-fields">
        <el-collapse-item name="details" title="更多信息（描述、类型、关联、计划人天）">
          <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
          <div class="task-form-grid">
            <el-form-item label="类型">
              <el-select v-model="form.kind" style="width:100%"><el-option v-for="kind in TASK_KINDS" :key="kind" :value="kind" :label="TASK_KIND_LABELS[kind]" /></el-select>
            </el-form-item>
            <el-form-item label="计划人天（可选）">
              <el-input-number v-model="form.estimateDays" :min="0" :step="0.5" placeholder="尚未估算" style="width:100%" />
            </el-form-item>
          </div>
          <el-form-item label="关联工作项（可选）">
            <el-select v-model="form.relatedNodeId" clearable filterable placeholder="不关联" style="width:100%">
              <el-option v-if="originalRelatedMissing && task?.relatedNodeId" :value="task.relatedNodeId" :label="task.relatedNodeName || '原关联工作项'" />
              <el-option v-for="item in workItems" :key="item.id" :value="item.id" :label="`${item.moduleName || '项目级'} / ${item.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="任务情况"><el-input v-model="form.situation" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="当前进展、问题或背景" /></el-form-item>
          <el-form-item label="处理方式"><el-input v-model="form.handling" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="准备怎么处理、已采取的措施或结果" /></el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="requestClose()">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ task ? '保存任务' : '创建任务' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.task-form-context{margin:0 0 20px;font-size:14px}
.task-form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.task-more-fields{margin-top:4px;border-bottom:0}
.task-more-fields :deep(.el-collapse-item__header){font-size:14px;color:var(--navy)}
@media(max-width:600px){.task-form-grid{grid-template-columns:1fr;gap:0}}
</style>
