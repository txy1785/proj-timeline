<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { ChangeRequest, ModuleItem, User } from '../api/types'
import { selectableMembers } from '../utils/member'

const props = defineProps<{ modelValue: boolean; module: ModuleItem; members: User[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved', v: ChangeRequest): void }>()
const form = reactive({ name: '', description: '', ownerId: 0, reason: '' })
const saving = ref(false)

// 每次打开都用模块当前值预填，避免残留上一次的申请内容
watch(() => props.modelValue, (open) => {
  if (!open) return
  Object.assign(form, { name: props.module.name, description: props.module.description || '', ownerId: props.module.ownerId, reason: '' })
})

async function submit() {
  if (saving.value) return
  if (!form.name.trim()) { ElMessage.warning('请填写模块名称'); return }
  if (!form.ownerId) { ElMessage.warning('请选择模块负责人'); return }
  saving.value = true
  try {
    const value = await api<ChangeRequest>('POST', `/modules/${props.module.id}/change-requests`, {
      name: form.name.trim(), description: form.description, ownerId: form.ownerId, reason: form.reason.trim()
    })
    emit('update:modelValue', false)
    ElMessage.success('变更申请已提交，等待项目经理审批')
    emit('saved', value)
  } catch (e) { ElMessage.error((e as Error).message) } finally { saving.value = false }
}
</script>
<template>
  <el-dialog :model-value="modelValue" title="申请变更模块" width="560" @update:model-value="emit('update:modelValue', $event)">
    <el-alert :title="`你没有「${module.name}」的直接编辑权限，提交后由项目经理审批，通过后自动生效。`" type="info" :closable="false" style="margin-bottom:14px" />
    <el-form label-position="top">
      <el-form-item label="模块名称" required>
        <el-input v-model="form.name" maxlength="100" />
      </el-form-item>
      <el-form-item label="模块描述">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="模块负责人" required>
        <el-select v-model="form.ownerId" style="width:100%">
          <el-option v-for="user in selectableMembers(members)" :key="user.id" :label="user.name" :value="user.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="申请说明">
        <el-input v-model="form.reason" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="为什么需要这样改，便于项目经理判断" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">提交申请</el-button>
    </template>
  </el-dialog>
</template>
