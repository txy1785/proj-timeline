<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, Task, TaskStatus, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { TASK_STATUSES, TASK_STATUS_LABELS } from '../utils/task'
const id=Number(useRoute().params.id); const auth=useAuthStore(); const tasks=ref<Task[]>([]); const node=ref<NodeItem>(); const members=ref<User[]>([])
const columns=computed(()=>TASK_STATUSES.map(key=>({key,label:TASK_STATUS_LABELS[key]})))
const grouped=computed(()=>Object.fromEntries(columns.value.map(c=>[c.key,tasks.value.filter(t=>t.status===c.key)])))
const membership=computed(()=>members.value.find(item=>item.id===auth.user?.id))
const archived=computed(()=>node.value?.projectStatus==='ARCHIVED')
function canManage(task:Task){return !auth.isViewer&&!archived.value&&(auth.isAdmin||membership.value?.projectRole==='PM'||node.value?.ownerId===auth.user?.id||node.value?.moduleOwnerId===auth.user?.id||task.assigneeId===auth.user?.id)}
async function load(){try{tasks.value=await api<Task[]>('GET',`/nodes/${id}/tasks`)}catch(e){ElMessage.error((e as Error).message)}}
async function loadContext(){try{node.value=await api<NodeItem>('GET',`/nodes/${id}`);members.value=await api<User[]>('GET',`/projects/${node.value.projectId}/members`)}catch(e){ElMessage.error((e as Error).message)}}
async function move(task:Task,status:TaskStatus){try{await api('PUT',`/tasks/${task.id}/status`,{status});await load()}catch(e){ElMessage.error((e as Error).message)}}
function changeStatus(task: Task, value: unknown) { void move(task, value as TaskStatus) }
onMounted(async()=>{await Promise.all([load(),loadContext()])})
</script>
<template><div><header class="page-head"><div><h1>任务看板</h1><p>按状态推进节点下的执行任务。</p></div><RouterLink :to="`/nodes/${id}`"><el-button>返回节点</el-button></RouterLink></header><div class="grid kanban"><section v-for="column in columns" :key="column.key" class="kanban-column"><strong>{{column.label}}</strong><el-tag size="small" style="float:right">{{grouped[column.key].length}}</el-tag><article v-for="task in grouped[column.key]" :key="task.id" class="task-card"><strong>{{task.name}}</strong><p class="muted">{{task.assigneeName||'未指派'}}<template v-if="task.estimateDays"> · 计划 {{task.estimateDays}} 人天</template><template v-if="task.noteCount"> · 记录 {{task.noteCount}} 条</template></p><el-select v-if="canManage(task)" :model-value="task.status" size="small" style="width:100%" @change="changeStatus(task,$event)"><el-option v-for="item in columns" :key="item.key" :label="item.label" :value="item.key"/></el-select></article><div v-if="!grouped[column.key].length" class="empty">暂无任务</div></section></div></div></template>
