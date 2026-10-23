<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, Timeline } from '../api/types'

const props=defineProps<{ modelValue:boolean; projectId:number }>()
const emit=defineEmits<{ (e:'update:modelValue',v:boolean):void; (e:'saved'):void }>()
const saving=ref(false); const workItems=ref<NodeItem[]>([])
const form=reactive({name:'',description:'',situation:'',relatedNodeId:0})

watch(()=>props.modelValue,async open=>{
  if(!open)return
  Object.assign(form,{name:'',description:'',situation:'',relatedNodeId:0})
  try{
    const timeline=await api<Timeline>('GET',`/projects/${props.projectId}/timeline`)
    workItems.value=[...(timeline.projectWorkItems||[]),...timeline.modules.flatMap(m=>m.nodes||[])]
  }catch(e){ElMessage.error((e as Error).message)}
})

async function submit(){
  if(saving.value)return
  if(!form.name.trim()){ElMessage.warning('请填写缺陷标题');return}
  saving.value=true
  try{
    const result=await api<{routedNodeName?:string}>('POST',`/projects/${props.projectId}/bugs`,{name:form.name.trim(),description:form.description,situation:form.situation,relatedNodeId:form.relatedNodeId||undefined})
    emit('update:modelValue',false)
    ElMessage.success(result.routedNodeName?`已提交到「${result.routedNodeName}」`:'缺陷已提交')
    emit('saved')
  }catch(e){ElMessage.error((e as Error).message)}finally{saving.value=false}
}
</script>
<template><el-dialog :model-value="modelValue" title="提交缺陷" width="620" @update:model-value="emit('update:modelValue',$event)"><el-form label-position="top"><el-form-item label="缺陷标题" required><el-input v-model="form.name" maxlength="200" show-word-limit placeholder="一句话说明问题"/></el-form-item><el-form-item label="问题描述"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="现象、影响范围、复现步骤"/></el-form-item><el-form-item label="任务情况"><el-input v-model="form.situation" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="当前进展、问题背景或补充说明"/></el-form-item><el-form-item label="关联工作项（可选）"><el-select v-model="form.relatedNodeId" clearable filterable style="width:100%" placeholder="进行中的工作项会直接落到该工作项，否则进缺陷修复工作项"><el-option :value="0" label="不关联"/><el-option v-for="item in workItems" :key="item.id" :value="item.id" :label="`${item.moduleName||'项目级'} / ${item.name}（${item.status}）`"/></el-select></el-form-item></el-form><template #footer><el-button @click="emit('update:modelValue',false)">取消</el-button><el-button type="primary" :loading="saving" @click="submit">提交</el-button></template></el-dialog></template>
