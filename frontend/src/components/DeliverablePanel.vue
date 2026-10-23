<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { Deliverable, DeliverableStatus, User } from '../api/types'
import { selectableMembers } from '../utils/member'

const props=withDefaults(defineProps<{
  nodeId:number
  items:Deliverable[]
  members:User[]
  canManage:boolean
  nodeClosed:boolean
  archived?:boolean
}>(),{archived:false})
const emit=defineEmits<{ (e:'refresh'):void }>()

const dialog=ref(false)
const saving=ref(false)
const editingId=ref(0)
const fileOptions=ref<{id:number;label:string}[]>([])
const form=reactive({name:'',status:'WANTED' as DeliverableStatus,ownerId:undefined as number|undefined,remark:'',attachmentId:undefined as number|undefined})

const total=computed(()=>props.items.length)
const provided=computed(()=>props.items.filter(item=>item.status==='PROVIDED').length)
const providedPercent=computed(()=>total.value?Math.round(provided.value/total.value*100):0)
const canCreate=computed(()=>props.canManage&&!props.archived&&!props.nodeClosed)
const canEdit=computed(()=>props.canManage&&!props.archived)
const canDelete=computed(()=>props.canManage&&!props.archived&&!props.nodeClosed)
const memberOptions=computed(()=>selectableMembers(props.members))
const editing=computed(()=>props.items.find(item=>item.id===editingId.value))

async function loadFiles(){
  // 关闭工作项不能新增，但编辑已有项仍需选项：否则下拉会显示裸 id（文件已删除时也看不到“已删除”提示）
  if(props.nodeClosed&&!editingId.value)return
  try{
    const list=await api<{id:number;title:string;version:number}[]>('GET','/attachments',undefined,{ownerType:'NODE',ownerId:props.nodeId})
    const options=list.map(item=>({id:item.id,label:`${item.title}（v${item.version}）`}))
    // 已关联文件被软删除后不在列表接口里，补一条说明，避免编辑时显示成裸 id
    const item=editing.value
    if(item?.attachmentId&&!options.some(option=>option.id===item.attachmentId)){
      options.push({id:item.attachmentId,label:`${item.attachmentTitle||'未命名文件'}（文件已删除）`})
    }
    fileOptions.value=options
  }catch(e){ElMessage.error((e as Error).message)}
}

function openCreate(){
  editingId.value=0
  Object.assign(form,{name:'',status:'WANTED',ownerId:undefined,remark:'',attachmentId:undefined})
  dialog.value=true
  void loadFiles()
}

function openEdit(item:Deliverable){
  editingId.value=item.id
  Object.assign(form,{name:item.name,status:item.status,ownerId:item.ownerId,remark:item.remark||'',attachmentId:item.attachmentId})
  dialog.value=true
  void loadFiles()
}

async function submit(){
  if(saving.value)return
  if(!form.name.trim()){ElMessage.warning('请填写交付物名称');return}
  // 编辑时显式传 null 表示清空负责人或解绑文件；新建时 null 等价于未填
  const payload={name:form.name.trim(),status:form.status,ownerId:form.ownerId??null,remark:form.remark.trim(),attachmentId:form.attachmentId??null}
  saving.value=true
  try{
    if(editingId.value)await api('PUT',`/deliverables/${editingId.value}`,payload)
    else await api('POST',`/nodes/${props.nodeId}/deliverables`,payload)
    dialog.value=false
    ElMessage.success(editingId.value?'交付物已更新':'交付物已新增')
    emit('refresh')
  }catch(e){ElMessage.error((e as Error).message)}finally{saving.value=false}
}

async function remove(item:Deliverable){
  try{await ElMessageBox.confirm(`确认删除交付物「${item.name}」？关联文件不会被删除。`,'删除交付物',{type:'warning'})}catch{return}
  try{
    await api<void>('DELETE',`/deliverables/${item.id}`)
    ElMessage.success('交付物已删除')
    emit('refresh')
  }catch(e){ElMessage.error((e as Error).message)}
}
</script>
<template>
  <div class="deliverable-panel">
    <div class="deliverable-head">
      <div>
        <h2>交付物</h2>
        <p class="muted">本工作项要交出的资料清单：开发文档、接口文档、测试记录等。标为「已提供」表示资料已经存在，可关联本工作项已上传的文件，也可只写在备注里（例如放在外部仓库）。</p>
      </div>
      <div class="deliverable-actions">
        <el-tag :type="total&&provided===total?'success':'info'">已提供 {{provided}}/{{total}}</el-tag>
        <el-button v-if="canCreate" type="primary" plain @click="openCreate">新增交付物</el-button>
      </div>
    </div>
    <el-progress v-if="total" :percentage="providedPercent" :stroke-width="8" :show-text="false" style="margin:0 0 12px" />
    <el-table :data="items" empty-text="尚无交付物">
      <el-table-column prop="name" label="交付物" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.status==='PROVIDED'?'success':'info'" size="small">{{scope.row.status==='PROVIDED'?'已提供':'待提供'}}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="负责人" width="110">
        <template #default="scope">{{scope.row.ownerName||'—'}}</template>
      </el-table-column>
      <el-table-column label="关联文件" min-width="200">
        <template #default="scope">
          <template v-if="scope.row.attachmentId">
            {{scope.row.attachmentTitle||'文件'}}
            <el-tag v-if="scope.row.attachmentDeleted===1" size="small" type="warning" style="margin-left:6px">已删除</el-tag>
          </template>
          <span v-else class="muted">未关联</span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="130">
        <template #default="scope">
          <el-button v-if="canEdit" link type="primary" @click="openEdit(scope.row)">编辑</el-button>
          <el-button v-if="canDelete" link type="danger" @click="remove(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editingId?'编辑交付物':'新增交付物'" width="560">
      <el-alert v-if="nodeClosed" title="工作项已完成或已取消，只能修改交付物状态" type="info" :closable="false" style="margin-bottom:14px" />
      <el-form label-position="top">
        <el-form-item label="交付物名称" required>
          <el-input v-model="form.name" maxlength="200" :disabled="nodeClosed" placeholder="例如：接口文档 v1、联调记录、测试报告" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="WANTED">待提供</el-radio>
            <el-radio value="PROVIDED">已提供</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="deliverable-grid">
          <el-form-item label="负责人">
            <el-select v-model="form.ownerId" clearable :disabled="nodeClosed" placeholder="未指定" style="width:100%">
              <el-option v-for="user in memberOptions" :key="user.id" :label="user.name" :value="user.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="关联本工作项文件">
            <el-select v-model="form.attachmentId" clearable :disabled="nodeClosed" placeholder="可不关联" style="width:100%">
              <el-option v-for="option in fileOptions" :key="option.id" :label="option.label" :value="option.id" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="500" :disabled="nodeClosed" placeholder="资料位置、版本说明或注意事项" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{editingId?'保存':'新增'}}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.deliverable-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}
.deliverable-head h2{margin:0 0 4px}
.deliverable-head p{margin:0 0 14px;font-size:13px}
.deliverable-actions{display:flex;align-items:center;gap:10px;flex:none}
.deliverable-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}
</style>
