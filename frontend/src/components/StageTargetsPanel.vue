<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { StageTarget, Timeline } from '../api/types'

const props = defineProps<{ project: Timeline; canManage: boolean }>()
// saved also fires after 403/409 so the parent reloads stale project data
const emit = defineEmits<{ saved: [] }>()

const busy = ref(false)
const targetDialog = ref(false)
const targetBaseline = ref('')
const targetForm = reactive({ id: 0, name: '', description: '', targetDate: '', hasMetric: false, metricName: '', metricUnit: '', metricTarget: undefined as number | undefined, metricCurrent: undefined as number | undefined })
const targetDirty = computed(() => JSON.stringify(targetForm) !== targetBaseline.value)

async function perform(action: () => Promise<unknown>, success: string) {
  if (busy.value) return false
  busy.value = true
  try { await action(); ElMessage.success(success); emit('saved'); return true }
  catch (e) {
    ElMessage.error((e as Error).message)
    if (e instanceof ApiError && [403, 409].includes(e.status || 0)) emit('saved')
    return false
  }
  finally { busy.value = false }
}
function openTarget(item?: StageTarget) {
  Object.assign(targetForm, { id: item?.id || 0, name: item?.name || '', description: item?.description || '', targetDate: item?.targetDate || '',
    hasMetric: item?.metricTarget != null || !!item?.metricName, metricName: item?.metricName || '', metricUnit: item?.metricUnit || '', metricTarget: item?.metricTarget, metricCurrent: item?.metricCurrent })
  targetBaseline.value = JSON.stringify(targetForm)
  targetDialog.value = true
}
async function closeTarget() {
  if (busy.value) return
  if (targetDirty.value) {
    try { await ElMessageBox.confirm('阶段目标有未保存的修改，关闭后将放弃本次修改。', '未保存的阶段目标', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' }) } catch { return }
  }
  targetDialog.value = false
}
async function saveTarget() {
  if (!props.canManage || busy.value) return
  if (!targetForm.name.trim() || !targetForm.targetDate) { ElMessage.warning('请填写目标名称和日期'); return }
  if (targetForm.targetDate < props.project.planStart || (props.project.planEnd && targetForm.targetDate > props.project.planEnd)) { ElMessage.warning('目标日期应在项目计划范围内'); return }
  if (targetForm.hasMetric && (!targetForm.metricName.trim() || !targetForm.metricTarget || targetForm.metricTarget <= 0)) { ElMessage.warning('请填写指标名称和大于 0 的目标值'); return }
  const metric = targetForm.hasMetric ? { metricName: targetForm.metricName.trim(), metricUnit: targetForm.metricUnit.trim() || null, metricTarget: targetForm.metricTarget, metricCurrent: targetForm.metricCurrent ?? 0 }
    : { metricName: null, metricUnit: null, metricTarget: null, metricCurrent: null }
  const payload = { name: targetForm.name.trim(), description: targetForm.description, targetDate: targetForm.targetDate, ...metric }
  const saved = await perform(() => targetForm.id ? api('PUT', `/stage-targets/${targetForm.id}`, payload) : api('POST', `/projects/${props.project.id}/stage-targets`, payload), targetForm.id ? '阶段目标已更新' : '阶段目标已创建')
  if (saved) targetDialog.value = false
}
async function deleteTarget(item: StageTarget) {
  if (!props.canManage) return
  try { await ElMessageBox.confirm(`确认删除阶段性目标「${item.name}」？`, '删除阶段目标', { type: 'warning' }) } catch { return }
  await perform(() => api('DELETE', `/stage-targets/${item.id}`), '阶段目标已删除')
}
</script>

<template>
  <section class="surface">
    <div class="section-heading"><div><h2>项目阶段性目标</h2><p class="muted">项目在特定日期要达到的关键目标。模块只做功能分组，不承载阶段日期。</p></div><el-button v-if="canManage" type="primary" @click="openTarget()">新建目标</el-button></div>
    <el-table :data="project.stageTargets" empty-text="还没有阶段目标，可稍后按交付计划补充"><el-table-column prop="name" label="目标" min-width="170" /><el-table-column prop="targetDate" label="目标日期" width="125" /><el-table-column prop="description" label="说明" min-width="180" /><el-table-column label="量化指标（可选）" min-width="190"><template #default="scope"><template v-if="scope.row.metricName">{{ scope.row.metricName }}：{{ scope.row.metricCurrent ?? 0 }}/{{ scope.row.metricTarget }} {{ scope.row.metricUnit }}<small class="metric-line">达成 {{ scope.row.progress }}%</small></template><span v-else class="muted">日期型目标，不推断达成度</span></template></el-table-column><el-table-column v-if="canManage" label="操作" width="125"><template #default="scope"><el-button link type="primary" :disabled="busy" @click="openTarget(scope.row)">编辑</el-button><el-button link type="danger" :disabled="busy" @click="deleteTarget(scope.row)">删除</el-button></template></el-table-column></el-table>
  </section>
  <el-dialog v-model="targetDialog" :title="targetForm.id ? '编辑阶段性目标' : '新建阶段性目标'" width="min(580px, calc(100vw - 28px))" :close-on-click-modal="false" :before-close="closeTarget"><el-form label-position="top" :disabled="busy || !canManage"><el-form-item label="目标名称" required><el-input v-model="targetForm.name" /></el-form-item><el-form-item label="目标说明"><el-input v-model="targetForm.description" type="textarea" :rows="2" /></el-form-item><el-form-item label="目标日期" required><el-date-picker v-model="targetForm.targetDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item><el-checkbox v-model="targetForm.hasMetric">补充量化指标（可选）</el-checkbox><p class="muted">不填指标时保留日期型目标，不计算评分或自动推断完成。</p><template v-if="targetForm.hasMetric"><div class="form-grid"><el-form-item label="指标名称" required><el-input v-model="targetForm.metricName" maxlength="100" /></el-form-item><el-form-item label="单位"><el-input v-model="targetForm.metricUnit" maxlength="20" /></el-form-item></div><div class="form-grid"><el-form-item label="目标值" required><el-input-number v-model="targetForm.metricTarget" :min="0" :max="9999999999.99" :precision="2" style="width:100%" /></el-form-item><el-form-item label="当前值"><el-input-number v-model="targetForm.metricCurrent" :min="0" :max="9999999999.99" :precision="2" style="width:100%" /></el-form-item></div></template></el-form><template #footer><el-button :disabled="busy" @click="closeTarget">取消</el-button><el-button type="primary" :loading="busy" :disabled="!canManage" @click="saveTarget">保存目标</el-button></template></el-dialog>
</template>

<style scoped>
.section-heading{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:20px}.section-heading h2{margin:0}.section-heading p{line-height:1.7;margin:8px 0 0}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.metric-line{display:block;color:var(--muted);margin-top:5px}
@media(max-width:600px){.form-grid{grid-template-columns:1fr;gap:0}.section-heading{align-items:flex-start;flex-wrap:wrap}}
</style>
