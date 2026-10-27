<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { BugRouting, NodeItem, Timeline } from '../api/types'
import { NODE_STATUS_LABELS } from '../utils/workItem'

const props = defineProps<{ modelValue: boolean; projectId: number }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'saved'): void }>()
const router = useRouter()
const saving = ref(false)
const previewing = ref(false)
const workItems = ref<NodeItem[]>([])
const listError = ref('')
const routing = ref<BugRouting>()
const routeError = ref('')
const advanced = ref('')
const receipt = ref<{ id: number; routedNodeId: number; routedNodeName: string; assigneeName?: string }>()
const form = reactive({ name: '', description: '', situation: '', relatedNodeId: 0 })
let generation = 0
let opening = 0

async function refreshRouting() {
  const current = ++generation
  previewing.value = true
  routing.value = undefined
  routeError.value = ''
  try {
    const result = await api<BugRouting>('GET', `/projects/${props.projectId}/bug-routing`, undefined,
      { relatedNodeId: form.relatedNodeId || undefined })
    if (current === generation && props.modelValue) routing.value = result
  } catch (e) {
    if (current === generation) routeError.value = (e as Error).message
  } finally { if (current === generation) previewing.value = false }
}

watch(() => props.modelValue, async open => {
  const currentOpening = ++opening
  generation++
  if (!open) return
  receipt.value = undefined
  listError.value = ''
  workItems.value = []
  advanced.value = ''
  Object.assign(form, { name: '', description: '', situation: '', relatedNodeId: 0 })
  void refreshRouting()
  const projectId = props.projectId
  try {
    const timeline = await api<Timeline>('GET', `/projects/${projectId}/timeline`)
    if (!props.modelValue || props.projectId !== projectId || currentOpening !== opening) return
    workItems.value = [...(timeline.projectWorkItems || []), ...timeline.modules.flatMap(module =>
      (module.nodes || []).map(node => ({ ...node, moduleName: module.name })))]
  } catch (e) { if (props.modelValue) listError.value = (e as Error).message }
})

async function submit() {
  if (saving.value || previewing.value) return
  if (!form.name.trim()) { ElMessage.warning('请填写缺陷标题'); return }
  if (!routing.value?.canSubmit) { ElMessage.warning('请先确认可接收这个缺陷的工作项'); return }
  saving.value = true
  try {
    receipt.value = await api('POST', `/projects/${props.projectId}/bugs`, {
      name: form.name.trim(), description: form.description, situation: form.situation,
      relatedNodeId: form.relatedNodeId || undefined
    })
    emit('saved')
  } catch (e) {
    ElMessage.error((e as Error).message)
    await refreshRouting()
  } finally { saving.value = false }
}

function openSubmitted() {
  if (!receipt.value) return
  emit('update:modelValue', false)
  router.push(`/nodes/${receipt.value.routedNodeId}?task=${receipt.value.id}`)
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="receipt ? '缺陷已提交' : '提交缺陷'" width="min(620px, calc(100vw - 28px))" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @update:model-value="emit('update:modelValue', $event)">
    <template v-if="receipt">
      <el-result icon="success" title="缺陷已提交" :sub-title="`已提交到「${receipt.routedNodeName}」${receipt.assigneeName ? '，由' + receipt.assigneeName + '处理。' : '。'}`" />
      <p class="muted">提交时已按最新工作项状态确定实际接收位置。</p>
    </template>
    <el-form v-else label-position="top" :disabled="saving" @submit.prevent="submit">
      <el-form-item label="缺陷标题" required><el-input v-model="form.name" maxlength="200" placeholder="一句话说明问题" /></el-form-item>
      <el-form-item label="问题描述（可选）"><el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" placeholder="现象、复现步骤、预期与实际结果" /></el-form-item>
      <el-form-item label="关联工作项（可选）">
        <el-select v-model="form.relatedNodeId" filterable style="width:100%" @change="refreshRouting">
          <el-option label="不关联" :value="0" />
          <el-option v-for="item in workItems" :key="item.id" :value="item.id" :label="`${item.moduleName || '项目级'} / ${item.name} · ${NODE_STATUS_LABELS[item.status]}`" />
        </el-select>
        <small v-if="listError" class="error-text">工作项列表加载失败：{{ listError }}</small>
      </el-form-item>
      <div v-loading="previewing" class="receiver">
        <el-alert v-if="routing" :type="routing.canSubmit ? 'info' : 'warning'" :closable="false" :title="routing.canSubmit ? `将提交给${routing.ownerName || '工作项负责人'} · ${routing.nodeName}` : routing.message" />
        <div v-else-if="routeError" class="error-text">接收位置暂不可用：{{ routeError }} <el-button link type="primary" @click="refreshRouting">重试</el-button></div>
        <p v-else class="muted">正在确认接收工作项…</p>
        <p v-if="routing && !routing.canSubmit" class="muted">可以改选一个进行中的关联工作项，或联系项目经理开启项目级缺陷修复工作项。</p>
      </div>
      <el-collapse v-model="advanced" accordion><el-collapse-item title="补充信息（任务情况）" name="more"><el-form-item label="任务情况"><el-input v-model="form.situation" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="可选的进展或问题背景" /></el-form-item></el-collapse-item></el-collapse>
    </el-form>
    <template #footer>
      <template v-if="receipt"><el-button @click="emit('update:modelValue', false)">完成</el-button><el-button type="primary" @click="openSubmitted">查看工作项与任务</el-button></template>
      <template v-else><el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="saving" :disabled="previewing || !routing?.canSubmit" @click="submit">提交缺陷</el-button></template>
    </template>
  </el-dialog>
</template>

<style scoped>
.receiver{margin:4px 0 18px;min-height:52px}.receiver p{line-height:1.6;margin:8px 0}.error-text{color:#bd3845;line-height:1.7}
</style>
