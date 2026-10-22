<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { NodeItem, Task, TaskNote, TaskStatus, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import DeliverablePanel from '../components/DeliverablePanel.vue'
import { TASK_NEXT_ACTION, TASK_STATUS_LABELS, TASK_STATUS_TYPES } from '../utils/task'
import { formatDateTime } from '../utils/format'
import { selectableMembers } from '../utils/member'
const route=useRoute(); const router=useRouter(); const auth=useAuthStore(); const id=Number(route.params.id)
const node=ref<NodeItem>(); const members=ref<User[]>([])
const taskDialog=ref(false); const detailDialog=ref(false); const current=ref<Task>()
const notes=ref<TaskNote[]>([]); const noteText=ref(''); const savingNote=ref(false); const savingStatus=ref(false); const savingTask=ref(false); const busyTaskId=ref(0)
type TaskForm={id:number;name:string;description:string;situation:string;handling:string;dueDate:string;estimateDays?:number;assigneeId:number}
const taskForm=reactive<TaskForm>({id:0,name:'',description:'',situation:'',handling:'',dueDate:'',estimateDays:1,assigneeId:0})
const readOnly=computed(()=>auth.isViewer)
const membership=computed(()=>members.value.find(item=>item.id===auth.user?.id))
const archived=computed(()=>node.value?.projectStatus==='ARCHIVED')
const nodeClosed=computed(()=>node.value?.status==='DONE'||node.value?.status==='CANCELLED')
const taskTotal=computed(()=>node.value?.totalTasks??0)
const taskDone=computed(()=>node.value?.doneTasks??0)
const isManager=computed(()=>!!(auth.isAdmin||membership.value?.projectRole==='PM'||node.value?.ownerId===auth.user?.id||node.value?.moduleOwnerId===auth.user?.id))
const canManageFiles=computed(()=>!auth.isViewer&&isManager.value)
const canManageTaskContent=(task:Task)=>!readOnly.value&&!archived.value&&(isManager.value||task.assigneeId===auth.user?.id)
const canManageTaskRow=(task:Task)=>canManageTaskContent(task)&&!nodeClosed.value
const isTaskManager=computed(()=>!readOnly.value&&!archived.value&&!nodeClosed.value&&isManager.value)
const canManageTask=computed(()=>{
  const task=current.value
  if(!task)return false
  return canManageTaskContent(task)
})
const canChangeTaskStatus=computed(()=>canManageTask.value&&!nodeClosed.value)
// 列表里的快捷操作：按当前状态给下一步（待完成→开始，进行中→完成，已完成→重新开始）
const NEXT_ACTION=TASK_NEXT_ACTION
// 带 expectedStatus 一起提交，多标签页/并发下状态已变会返回 409 而不是静默改错；busy 期间锁住所有快捷按钮
async function quickStatus(task:Task){if(busyTaskId.value)return;const action=NEXT_ACTION[task.status];busyTaskId.value=task.id;try{await api<Task>('PUT',`/tasks/${task.id}/status`,{status:action.next,expectedStatus:task.status});ElMessage.success(`「${task.name}」已${action.label}`);await loadNode()}catch(e){ElMessage.error((e as Error).message);await loadNode()}finally{busyTaskId.value=0}}
// 记录的删除口径：作者本人，或非执行人的管理者（执行人不能删他人的记录）
const canDeleteNote=(note:TaskNote)=>canManageTask.value&&(isManager.value||note.authorId===auth.user?.id)
const STATUS_LABELS=TASK_STATUS_LABELS; const STATUS_TYPES=TASK_STATUS_TYPES
async function loadNode(){try{node.value=await api<NodeItem>('GET',`/nodes/${id}`);const open=current.value;if(open){const latest=(node.value.tasks||[]).find(item=>item.id===open.id);if(latest)current.value=latest}}catch(e){ElMessage.error((e as Error).message)}}
async function loadMembers(){if(!node.value)return;try{members.value=await api<User[]>('GET',`/projects/${node.value.projectId}/members`)}catch(e){ElMessage.error((e as Error).message)}}
async function load(){await loadNode();await loadMembers()}
async function setStatus(status:string){try{await api('POST',`/nodes/${id}/status`,{status});await loadNode()}catch(e){ElMessage.error((e as Error).message)}}
async function block(){try{await api('POST',`/nodes/${id}/block`,{reason:'进展受阻，待负责人补充处理'});await loadNode()}catch(e){ElMessage.error((e as Error).message)}}
async function unblock(){try{await api('POST',`/nodes/${id}/unblock`);await loadNode()}catch(e){ElMessage.error((e as Error).message)}}
function openCreateTask(){Object.assign(taskForm,{id:0,name:'',description:'',situation:'',handling:'',dueDate:'',estimateDays:1,assigneeId:node.value?.ownerId||0});taskDialog.value=true}
function openEditTask(task:Task){Object.assign(taskForm,{id:task.id,name:task.name,description:task.description||'',situation:task.situation||'',handling:task.handling||'',dueDate:task.dueDate||'',estimateDays:task.estimateDays,assigneeId:task.assigneeId});taskDialog.value=true}
async function saveTask(){if(savingTask.value)return;if(!taskForm.name.trim()){ElMessage.warning('请填写任务名称');return}const payload={name:taskForm.name.trim(),description:taskForm.description,situation:taskForm.situation,handling:taskForm.handling,dueDate:taskForm.dueDate||undefined,estimateDays:taskForm.estimateDays,assigneeId:taskForm.assigneeId||undefined};savingTask.value=true;try{if(taskForm.id)await api('PUT',`/tasks/${taskForm.id}`,payload);else await api('POST',`/nodes/${id}/tasks`,payload);taskDialog.value=false;ElMessage.success(taskForm.id?'任务已更新':'任务已创建');await loadNode()}catch(e){ElMessage.error((e as Error).message)}finally{savingTask.value=false}}
async function removeTask(task:Task){try{await ElMessageBox.confirm(`确认删除任务「${task.name}」？其进展记录与工时会一并删除，附件会标记删除。`,'删除任务',{type:'warning'})}catch{return}try{await api<void>('DELETE',`/tasks/${task.id}`);if(current.value?.id===task.id){detailDialog.value=false;current.value=undefined}ElMessage.success('任务已删除');await loadNode()}catch(e){ElMessage.error((e as Error).message)}}
async function openDetail(task:Task){current.value=task;detailDialog.value=true;notes.value=[];noteText.value='';await loadNotes(task.id)}
async function loadNotes(taskId:number){try{const value=await api<TaskNote[]>('GET',`/tasks/${taskId}/notes`);if(current.value?.id===taskId)notes.value=value}catch(e){ElMessage.error((e as Error).message)}}
async function setTaskStatus(status:TaskStatus){const task=current.value;if(!task||savingStatus.value)return;savingStatus.value=true;try{current.value=await api<Task>('PUT',`/tasks/${task.id}/status`,{status});await loadNode()}catch(e){ElMessage.error((e as Error).message)}finally{savingStatus.value=false}}
async function addNote(){const task=current.value;if(!task)return;if(!noteText.value.trim()){ElMessage.warning('请填写记录内容');return}savingNote.value=true;try{await api<TaskNote>('POST',`/tasks/${task.id}/notes`,{content:noteText.value.trim()});noteText.value='';await loadNotes(task.id);await loadNode()}catch(e){ElMessage.error((e as Error).message)}finally{savingNote.value=false}}
async function removeNote(note:TaskNote){const task=current.value;if(!task)return;try{await ElMessageBox.confirm('确定删除这条记录？','删除记录',{type:'warning'})}catch{return}try{await api<void>('DELETE',`/task-notes/${note.id}`);await loadNotes(task.id);await loadNode()}catch(e){ElMessage.error((e as Error).message)}}
onMounted(load)
</script>
<template><div><header class="page-head"><div><el-breadcrumb separator="/"><el-breadcrumb-item :to="`/projects/${node?.projectId}/timeline`">时间线</el-breadcrumb-item><el-breadcrumb-item>{{node?.name}}</el-breadcrumb-item></el-breadcrumb><h1 style="margin-top:14px">{{node?.name}}</h1><p>{{node?.moduleName}} · {{node?.ownerName}} · {{node?.planStart}} 至 {{node?.planEnd}}</p></div><el-button @click="router.push(`/nodes/${id}/kanban`)">任务看板</el-button></header>
<section class="surface" style="margin-bottom:18px"><div style="display:grid;grid-template-columns:160px 1fr auto;gap:20px;align-items:center"><el-progress type="circle" :percentage="node?.progress||0" /><div><el-tag>{{node?.status}}</el-tag><el-tag v-if="node?.flags.overdue" type="danger" style="margin-left:8px">已延期</el-tag><el-tag v-if="node?.flags.blocked" type="warning" style="margin-left:8px">受阻</el-tag><p>{{node?.description||'暂无节点说明'}}</p><p class="muted progress-hint"><template v-if="node?.status==='DONE'">节点已完成，进度固定为 100%。</template><template v-else-if="node?.status==='CANCELLED'">节点已取消，不计入模块与项目进度。</template><template v-else-if="taskTotal">进度由任务完成率自动计算：已完成 {{taskDone}}/{{taskTotal}} 个任务。</template><template v-else>本节点暂无任务，进度按状态估算（进行中按 50%）。</template></p></div><div v-if="!readOnly" style="display:grid;gap:8px"><el-button v-if="node?.status==='NOT_STARTED'" type="primary" @click="setStatus('IN_PROGRESS')">开始节点</el-button><el-button v-if="node?.status==='IN_PROGRESS'" type="success" @click="setStatus('DONE')">完成节点</el-button><el-button v-if="node?.flags.blocked" @click="unblock">解除受阻</el-button><el-button v-else type="warning" plain @click="block">标记受阻</el-button></div></div></section>
<section class="surface"><div style="display:flex;justify-content:space-between;align-items:center"><div><h2>任务</h2><p class="muted">{{nodeClosed?'节点已完成或已取消，不能再新增或重新打开任务。':'任务用状态标记进度：待完成 / 进行中 / 已完成。点「详情」可改状态、写记录、挂附件。'}}</p></div><el-button v-if="canManageFiles&&!archived&&!nodeClosed" type="primary" plain @click="openCreateTask">新增任务</el-button></div><el-table :data="node?.tasks||[]"><el-table-column prop="name" label="任务" min-width="180"/><el-table-column prop="assigneeName" label="执行人" width="110"/><el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="STATUS_TYPES[scope.row.status as TaskStatus]" size="small">{{STATUS_LABELS[scope.row.status as TaskStatus]}}</el-tag></template></el-table-column><el-table-column prop="estimateDays" label="计划人天" width="100"/><el-table-column prop="dueDate" label="截止日" width="120"/><el-table-column label="记录" width="80"><template #default="scope">{{scope.row.noteCount?`${scope.row.noteCount} 条`:'—'}}</template></el-table-column><el-table-column label="操作" width="240"><template #default="scope"><el-button v-if="canManageTaskRow(scope.row)" link type="primary" :loading="busyTaskId===scope.row.id" :disabled="!!busyTaskId&&busyTaskId!==scope.row.id" @click="quickStatus(scope.row)">{{ NEXT_ACTION[scope.row.status as TaskStatus].label }}</el-button><el-button link type="primary" @click="openDetail(scope.row)">详情</el-button><el-button v-if="isTaskManager" link type="primary" @click="openEditTask(scope.row)">编辑</el-button><el-button v-if="isTaskManager" link type="danger" @click="removeTask(scope.row)">删除</el-button></template></el-table-column></el-table></section>
<section class="surface" style="margin-top:18px"><DeliverablePanel :node-id="id" :items="node?.deliverables||[]" :members="members" :can-manage="canManageFiles" :node-closed="nodeClosed" :archived="archived" @refresh="loadNode" /></section>
<section class="surface" style="margin-top:18px"><AttachmentPanel owner-type="NODE" :owner-id="id" :can-manage="canManageFiles" :archived="archived" :show-category-nav="false" hint="节点负责人上传本节点的设计说明、流程图、自测记录和交付物；项目成员可下载。" /></section>
<el-dialog v-model="taskDialog" :title="taskForm.id?'编辑任务':'新增任务'" width="620"><el-form label-position="top"><el-form-item label="任务名称" required><el-input v-model="taskForm.name" placeholder="例如：完成登录接口联调"/></el-form-item><el-form-item label="描述"><el-input v-model="taskForm.description" type="textarea"/></el-form-item><el-form-item label="任务情况"><el-input v-model="taskForm.situation" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="当前进展、遇到的问题或背景"/></el-form-item><el-form-item label="处理方式"><el-input v-model="taskForm.handling" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="准备怎么处理、已采取的措施或结果"/></el-form-item><div style="display:grid;grid-template-columns:1fr 1fr;gap:14px"><el-form-item label="执行人"><el-select v-model="taskForm.assigneeId" style="width:100%"><el-option v-for="u in selectableMembers(members)" :key="u.id" :label="u.name" :value="u.id"/></el-select></el-form-item><el-form-item label="截止日期"><el-date-picker v-model="taskForm.dueDate" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item></div><el-form-item label="计划人天"><el-input-number v-model="taskForm.estimateDays" :step="0.5" :min="0" style="width:100%"/></el-form-item></el-form><template #footer><el-button @click="taskDialog=false">取消</el-button><el-button type="primary" :loading="savingTask" @click="saveTask">{{taskForm.id?'保存':'创建'}}</el-button></template></el-dialog>
<el-dialog v-model="detailDialog" :title="current?.name||'任务详情'" width="760"><div class="task-detail"><div class="task-meta"><span>执行人 {{current?.assigneeName}}</span><span v-if="current?.dueDate">截止 {{current.dueDate}}</span><span v-if="current?.estimateDays">计划 {{current.estimateDays}} 人天</span></div><div v-if="current?.description" class="task-text"><span class="muted">描述</span><p>{{current.description}}</p></div><div v-if="current?.situation" class="task-text"><span class="muted">任务情况</span><p>{{current.situation}}</p></div><div v-if="current?.handling" class="task-text"><span class="muted">处理方式</span><p>{{current.handling}}</p></div><div class="task-status"><span class="muted">状态</span><el-button-group><el-button type="primary" :plain="current?.status!=='TODO'" :disabled="!canChangeTaskStatus||savingStatus" @click="setTaskStatus('TODO')">待完成</el-button><el-button type="primary" :plain="current?.status!=='DOING'" :disabled="!canChangeTaskStatus||savingStatus" @click="setTaskStatus('DOING')">进行中</el-button><el-button type="primary" :plain="current?.status!=='DONE'" :disabled="!canChangeTaskStatus||savingStatus" @click="setTaskStatus('DONE')">已完成</el-button></el-button-group></div><el-input v-if="canManageTask" v-model="noteText" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="记录完成思路、结果或备注，例如：接口已联调，异常分支由前端兜底"/><el-button v-if="canManageTask" type="primary" :loading="savingNote" style="margin:10px 0 4px" @click="addNote">追加记录</el-button><el-empty v-if="!notes.length" description="暂无记录" :image-size="60"/><div v-for="note in notes" :key="note.id" class="note-item"><div class="note-head"><strong>{{note.authorName}}</strong><span class="muted">{{formatDateTime(note.createdAt)}}</span><el-button v-if="canDeleteNote(note)" link type="danger" @click="removeNote(note)">删除</el-button></div><p>{{note.content}}</p></div></div><el-divider content-position="left">附件</el-divider><AttachmentPanel v-if="current" :key="current.id" owner-type="TASK" :owner-id="current.id" :can-manage="canManageTask" :archived="archived" :show-category-nav="false" hint="任务执行人可上传交付物或参考资料，项目成员可下载。"/></el-dialog></div></template>
<style scoped>
.progress-hint{margin:6px 0 0;font-size:13px}
.task-meta{display:flex;gap:16px;font-size:13px;color:var(--muted);margin-bottom:12px}
.task-text{margin:0 0 10px;font-size:13px}
.task-text p{margin:2px 0 0;white-space:pre-wrap}
.task-status{display:flex;align-items:center;gap:12px;margin-bottom:14px}
.note-item{border-top:1px solid var(--line);padding:10px 0}
.note-head{display:flex;align-items:center;gap:10px;font-size:12px;margin-bottom:4px}
.note-item p{margin:0;white-space:pre-wrap}
</style>
