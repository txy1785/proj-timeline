<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { ModuleItem, User } from '../api/types'

const props=defineProps<{ modelValue:boolean; projectId:number; module?:ModuleItem|null; members:User[]; defaultOwnerId?:number }>()
const emit=defineEmits<{ (e:'update:modelValue',v:boolean):void; (e:'saved'):void }>()
const form=reactive({id:0,name:'',description:'',ownerId:0})
const saving=ref(false)
const title=computed(()=>form.id?'编辑模块':'新建模块')
watch(()=>props.modelValue,open=>{
  if(!open)return
  const item=props.module
  Object.assign(form,{id:item?.id||0,name:item?.name||'',description:item?.description||'',ownerId:item?.ownerId||props.defaultOwnerId||0})
})
async function save(){
  if(saving.value)return
  if(!form.name.trim()||!form.ownerId){ElMessage.warning('请填写模块名称和负责人');return}
  saving.value=true
  try{
    const payload={name:form.name.trim(),description:form.description,ownerId:form.ownerId}
    if(form.id)await api('PUT',`/modules/${form.id}`,payload)
    else await api('POST',`/projects/${props.projectId}/modules`,payload)
    emit('update:modelValue',false);ElMessage.success(form.id?'模块已更新':'模块已创建');emit('saved')
  }catch(e){ElMessage.error((e as Error).message)}finally{saving.value=false}
}
</script>
<template><el-dialog :model-value="modelValue" :title="title" width="560" @update:model-value="emit('update:modelValue',$event)"><el-form label-position="top"><el-form-item label="模块名称" required><el-input v-model="form.name"/></el-form-item><el-form-item label="模块描述"><el-input v-model="form.description" type="textarea"/></el-form-item><el-form-item label="模块负责人" required><el-select v-model="form.ownerId" style="width:100%"><el-option v-for="user in members.filter(item=>item.active&&item.globalRole!=='VIEWER')" :key="user.id" :label="user.name" :value="user.id"/></el-select></el-form-item></el-form><template #footer><el-button @click="emit('update:modelValue',false)">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog></template>
