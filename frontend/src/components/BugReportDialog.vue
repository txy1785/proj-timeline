<script setup lang="ts">
import { computed, ref, reactive, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { BugRouting, NodeItem, Timeline } from '../api/types'
import { NODE_STATUS_LABELS } from '../utils/workItem'

const props = defineProps<{ modelValue: boolean; projectId: number; nodeId?: number }>()
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
const related = computed(() => workItems.value.find(item => item.id === form.relatedNodeId))
const dirty = computed(() => !!(form.name.trim() || form.description.trim() || form.situation.trim()))
let generation = 0
let opening = 0

async function refreshRouting() {
  const current = ++generation
  routing.value = undefined; routeError.value = ''
  if (!form.relatedNodeId) { previewing.value = false; return }
  previewing.value = true
  try {
    const result = await api<BugRouting>('GET', `/projects/${props.projectId}/bug-routing`, undefined, { relatedNodeId: form.relatedNodeId })
    if (current === generation && props.modelValue) routing.value = result
  } catch (e) { if (current === generation) routeError.value = (e as Error).message }
  finally { if (current === generation) previewing.value = false }
}
watch(() => props.modelValue, async open => {
  const currentOpening = ++opening
  generation++
  if (!open) return
  receipt.value = undefined; listError.value = ''; workItems.value = []; advanced.value = ''
  Object.assign(form, { name: '', description: '', situation: '', relatedNodeId: props.nodeId || 0 })
  void refreshRouting()
  const projectId = props.projectId
  try {
    const timeline = await api<Timeline>('GET', `/projects/${projectId}/timeline`)
    if (!props.modelValue || props.projectId !== projectId || currentOpening !== opening) return
    workItems.value = [...(timeline.projectWorkItems || []), ...timeline.modules.flatMap(module => (module.nodes || []).map(node => ({ ...node, moduleName: module.name })))].filter(node => node.status !== 'CANCELLED')
  } catch (e) { if (props.modelValue && currentOpening === opening) listError.value = (e as Error).message }
})
async function allowClose() {
  if (saving.value) return false
  if (!dirty.value || receipt.value) return true
  try { await ElMessageBox.confirm('Bug尚未报告，关闭后将放弃本次输入。', '未提交的Bug', { confirmButtonText: '放弃并关闭', cancelButtonText: '继续填写', type: 'warning' }); return true }
  catch { return false }
}
async function requestClose(done?: () => void) { if (await allowClose()) { if (done) done(); else emit('update:modelValue', false) } }
onBeforeRouteLeave(async () => !props.modelValue || await allowClose())
async function submit() {
  if (saving.value || previewing.value) return
  if (!form.name.trim()) { ElMessage.warning('请填写Bug标题'); return }
  if (!form.relatedNodeId) { ElMessage.warning('请选择关联功能'); return }
  if (!routing.value?.canSubmit) { ElMessage.warning('请先确认功能与负责人可接收这个Bug'); return }
  saving.value = true
  try {
    receipt.value = await api('POST', `/projects/${props.projectId}/bugs`, { name: form.name.trim(), description: form.description, situation: form.situation, relatedNodeId: form.relatedNodeId })
    emit('saved')
  } catch (e) { ElMessage.error((e as Error).message); await refreshRouting() }
  finally { saving.value = false }
}
function openSubmitted() {
  if (!receipt.value) return
  emit('update:modelValue', false)
  void router.push(`/nodes/${receipt.value.routedNodeId}?task=${receipt.value.id}`)
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="receipt ? 'Bug已报告' : '报告 Bug'" width="min(620px, calc(100vw - 28px))" :before-close="requestClose" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @update:model-value="emit('update:modelValue', $event)">
    <template v-if="receipt"><el-result icon="success" title="Bug已报告" :sub-title="`已关联「${receipt.routedNodeName}」${receipt.assigneeName ? '，由' + receipt.assigneeName + '处理。' : '。'}`" /><p class="muted">可继续补充复现截图、修复记录与验证资料。</p></template>
    <el-form v-else label-position="top" :disabled="saving" @submit.prevent="submit">
      <el-form-item label="Bug标题" required><el-input v-model="form.name" maxlength="200" placeholder="一句话说明出现的问题" /></el-form-item>
      <el-form-item label="关联功能" required><el-input v-if="nodeId" :model-value="related?.name || routing?.nodeName || '正在读取关联功能'" readonly /><el-select v-else :model-value="form.relatedNodeId || undefined" filterable placeholder="选择出现问题的工作项" style="width:100%" @update:model-value="form.relatedNodeId = Number($event)" @change="refreshRouting"><el-option v-for="item in workItems" :key="item.id" :value="item.id" :label="`${item.moduleName || '项目级'} / ${item.name} · ${NODE_STATUS_LABELS[item.status]}`" /></el-select><small v-if="listError" class="error-text">工作项读取失败：{{ listError }}</small></el-form-item>
      <el-form-item label="问题描述（可选）"><el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" placeholder="复现步骤、预期效果与实际现象" /></el-form-item>
      <div v-loading="previewing" class="receiver"><el-alert v-if="routing" :type="routing.canSubmit ? 'info' : 'warning'" :closable="false" :title="routing.canSubmit ? `负责人：${routing.ownerName || '功能负责人'} · ${routing.nodeName}` : routing.message" /><div v-else-if="routeError" class="error-text">负责人暂不可用：{{ routeError }} <el-button link type="primary" @click="refreshRouting">重试</el-button></div><p v-else class="muted">{{ form.relatedNodeId ? '正在确认负责人…' : '请选择关联功能；已交付的功能也可以报告Bug。' }}</p></div>
      <el-collapse v-model="advanced" accordion><el-collapse-item title="补充问题背景" name="more"><el-form-item label="问题背景"><el-input v-model="form.situation" type="textarea" :rows="2" maxlength="2000" show-word-limit /></el-form-item></el-collapse-item></el-collapse>
    </el-form>
    <template #footer><template v-if="receipt"><el-button @click="requestClose()">完成</el-button><el-button type="primary" @click="openSubmitted">查看Bug</el-button></template><template v-else><el-button :disabled="saving" @click="requestClose()">取消</el-button><el-button type="primary" :loading="saving" :disabled="previewing || !routing?.canSubmit" @click="submit">报告Bug</el-button></template></template>
  </el-dialog>
</template>
<style scoped>.receiver{margin:4px 0 18px;min-height:52px}.receiver p{line-height:1.6;margin:8px 0}.error-text{color:#bd3845;line-height:1.7}</style>
