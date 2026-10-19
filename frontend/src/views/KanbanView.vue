<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { Task, TaskStatus } from '../api/types'
import { useAuthStore } from '../stores/auth'
const id=Number(useRoute().params.id); const auth=useAuthStore(); const tasks=ref<Task[]>([])
const columns:{key:TaskStatus;label:string}[]=[{key:'TODO',label:'待办'},{key:'DOING',label:'进行中'},{key:'DONE',label:'已完成'}]
const grouped=computed(()=>Object.fromEntries(columns.map(c=>[c.key,tasks.value.filter(t=>t.status===c.key)])))
async function load(){try{tasks.value=await api<Task[]>('GET',`/nodes/${id}/tasks`)}catch(e){ElMessage.error((e as Error).message)}}
async function move(task:Task,status:TaskStatus){try{await api('PUT',`/tasks/${task.id}/status`,{status});await load()}catch(e){ElMessage.error((e as Error).message)}} onMounted(load)
function changeStatus(task: Task, value: unknown) { void move(task, value as TaskStatus) }
</script>
<template><div><header class="page-head"><div><h1>任务看板</h1><p>按状态推进节点下的执行任务。</p></div><RouterLink :to="`/nodes/${id}`"><el-button>返回节点</el-button></RouterLink></header><div class="grid kanban"><section v-for="column in columns" :key="column.key" class="kanban-column"><strong>{{column.label}}</strong><el-tag size="small" style="float:right">{{grouped[column.key].length}}</el-tag><article v-for="task in grouped[column.key]" :key="task.id" class="task-card"><strong>{{task.name}}</strong><p class="muted">{{task.assigneeName}} · {{task.estimateDays??'-'}} / {{task.actualDays??0}} 人天</p><el-select v-if="!auth.isViewer" :model-value="task.status" size="small" style="width:100%" @change="changeStatus(task,$event)"><el-option v-for="item in columns" :key="item.key" :label="item.label" :value="item.key"/></el-select></article></section></div></div></template>
