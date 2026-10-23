<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, User, WorkItemType } from '../api/types'
import { selectableMembers } from '../utils/member'
import { WORK_ITEM_TYPE_LABELS, WORK_ITEM_TYPES } from '../utils/workItem'

const props=defineProps<{ modelValue:boolean; projectId:number; moduleId?:number|null; workItem?:NodeItem|null; members:User[]; defaultOwnerId?:number; defaultPlanStart?:string; defaultPlanEnd?:string }>()
const emit=defineEmits<{ (e:'update:modelValue',v:boolean):void; (e:'saved'):void }>()
const saving=ref(false)
const form=reactive({id:0,name:'',description:'',type:'FEATURE' as WorkItemType,planStart:'',planEnd:'',ownerId:0})
const title=computed(()=>form.id?'编辑工作项':'新建工作项')

watch(()=>props.modelValue,open=>{
  if(!open)return
  const item=props.workItem
  Object.assign(form,{
    id:item?.id||0,
    name:item?.name||'',
    description:item?.description||'',
    type:item?.type||'FEATURE',
    planStart:item?.planStart||props.defaultPlanStart||'',
    planEnd:item?.planEnd||props.defaultPlanEnd||'',
    ownerId:item?.ownerId||props.defaultOwnerId||0
  })
})

async function save(){
  if(saving.value)return
  if(!form.name.trim()||!form.planStart||!form.planEnd){ElMessage.warning('请填写工作项名称和计划起止日期');return}
  if(form.planEnd<form.planStart){ElMessage.warning('计划结束日期不能早于开始日期');return}
  saving.value=true
  try{
    const payload={name:form.name.trim(),description:form.description,type:form.type,planStart:form.planStart,planEnd:form.planEnd,ownerId:form.ownerId||undefined}
    if(form.id)await api('PUT',`/nodes/${form.id}`,payload)
    else await api('POST',`/projects/${props.projectId}/nodes`,{...payload,moduleId:props.moduleId??undefined})
    emit('update:modelValue',false);ElMessage.success(form.id?'工作项已更新':'工作项已创建');emit('saved')
  }catch(e){ElMessage.error((e as Error).message)}finally{saving.value=false}
}
</script>
<template><el-dialog :model-value="modelValue" :title="title" width="600" @update:model-value="emit('update:modelValue',$event)"><el-form label-position="top"><el-form-item label="工作项名称" required><el-input v-model="form.name" placeholder="例如：登录与注册开发"/></el-form-item><el-form-item label="类型" required><el-select v-model="form.type" style="width:100%"><el-option v-for="type in WORK_ITEM_TYPES" :key="type" :label="WORK_ITEM_TYPE_LABELS[type]" :value="type"/></el-select></el-form-item><el-form-item label="说明"><el-input v-model="form.description" type="textarea"/></el-form-item><div style="display:grid;grid-template-columns:1fr 1fr;gap:14px"><el-form-item label="计划开始日期" required><el-date-picker v-model="form.planStart" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item><el-form-item label="计划结束日期" required><el-date-picker v-model="form.planEnd" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item></div><el-form-item label="负责人"><el-select v-model="form.ownerId" style="width:100%"><el-option v-for="user in selectableMembers(members)" :key="user.id" :label="user.name" :value="user.id"/></el-select></el-form-item></el-form><template #footer><el-button @click="emit('update:modelValue',false)">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog></template>
