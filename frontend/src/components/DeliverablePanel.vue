<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { api, upload } from '../api/client'
import type { Attachment, Deliverable, DeliverableFile, DeliverableStatus, User } from '../api/types'
import { selectableMembers } from '../utils/member'
import { MAX_ATTACHMENT_BYTES } from '../utils/attachment'

/** 与后端一致：一条交付记录最多关联的文件数。 */
const MAX_LINKED_FILES = 20
/** 列表里直接展示的文件名条数，其余折成 +N。 */
const SHOWN_FILE_TAGS = 3

const props=withDefaults(defineProps<{
  nodeId:number
  items:Deliverable[]
  members:User[]
  canManage:boolean
  nodeClosed:boolean
  archived?:boolean
}>(),{archived:false})
const emit=defineEmits<{ (e:'refresh'):void; (e:'files-changed'):void }>()

const dialog=ref(false)
const saving=ref(false)
const editingId=ref(0)
const fileOptions=ref<{id:number;label:string}[]>([])
const pendingFiles=ref<File[]>([])
const uploadProgress=ref('')
const form=reactive({name:'',status:'WANTED' as DeliverableStatus,ownerId:undefined as number|undefined,remark:'',attachmentIds:[] as number[]})

const total=computed(()=>props.items.length)
const provided=computed(()=>props.items.filter(item=>item.status==='PROVIDED').length)
const providedPercent=computed(()=>total.value?Math.round(provided.value/total.value*100):0)
const canCreate=computed(()=>props.canManage&&!props.archived&&!props.nodeClosed)
const canEdit=computed(()=>props.canManage&&!props.archived)
const canDelete=computed(()=>props.canManage&&!props.archived&&!props.nodeClosed)
const memberOptions=computed(()=>selectableMembers(props.members))
const editing=computed(()=>props.items.find(item=>item.id===editingId.value))
/** 已完成/已取消或归档时文件只读，但状态仍可改。 */
const fileLocked=computed(()=>props.nodeClosed||props.archived)

function linkedFiles(item:Deliverable):DeliverableFile[]{return item.attachments||[]}
function shownFiles(item:Deliverable):DeliverableFile[]{return linkedFiles(item).slice(0,SHOWN_FILE_TAGS)}
/** 列表接口与记录快照字段同构，标签文案统一由 optionLabel 派生。 */
type FileLabel={title?:string;originalName?:string;version?:number}
function fileName(file:FileLabel){return file.title||file.originalName||'未命名文件'}
function optionLabel(file:FileLabel){return file.version?`${fileName(file)}（v${file.version}）`:fileName(file)}

async function loadFiles(){
  // 关闭工作项不能新增，但编辑已有项仍需选项：否则下拉会显示裸 id（文件已删除时也看不到“已删除”提示）
  if(fileLocked.value&&!editingId.value)return
  try{
    const list=await api<{id:number;title:string;version:number}[]>('GET','/attachments',undefined,{ownerType:'NODE',ownerId:props.nodeId})
    const options=list.map(item=>({id:item.id,label:optionLabel(item)}))
    // 已关联文件被软删除后不再出现在列表接口里，按记录里的快照补一条并标出“文件已删除”
    const current=editing.value
    for(const file of current?linkedFiles(current):[]){
      const existing=options.find(option=>option.id===file.id)
      if(file.deleted===1){
        const label=`${fileName(file)}（文件已删除）`
        if(existing)existing.label=label
        else options.push({id:file.id,label})
      }else if(!existing){
        options.push({id:file.id,label:`${optionLabel(file)}（文件已删除）`})
      }
    }
    fileOptions.value=options
  }catch(e){ElMessage.error((e as Error).message)}
}

function openCreate(){
  editingId.value=0
  Object.assign(form,{name:'',status:'WANTED',ownerId:undefined,remark:'',attachmentIds:[]})
  pendingFiles.value=[]
  uploadProgress.value=''
  dialog.value=true
  void loadFiles()
}

function openEdit(item:Deliverable){
  editingId.value=item.id
  Object.assign(form,{name:item.name,status:item.status,ownerId:item.ownerId,remark:item.remark||'',attachmentIds:linkedFiles(item).map(file=>file.id)})
  pendingFiles.value=[]
  uploadProgress.value=''
  dialog.value=true
  void loadFiles()
}

/** el-upload 逐个回调，多选时每次进来一个文件。 */
function onFilesPicked(file:UploadFile){
  const raw=file.raw
  if(!raw)return
  if(raw.size>MAX_ATTACHMENT_BYTES){ElMessage.warning(`「${raw.name}」超过 50MB，未加入待上传列表`);return}
  pendingFiles.value.push(raw)
}

function removePending(index:number){pendingFiles.value=pendingFiles.value.filter((_,position)=>position!==index)}

function uploadBody(file:File){
  const body=new FormData()
  body.append('ownerType','NODE')
  body.append('ownerId',String(props.nodeId))
  body.append('category','NODE_DELIVERABLE')
  body.append('title',file.name)
  if(form.name.trim())body.append('remark',`交付要求：${form.name.trim()}`)
  body.append('file',file)
  return body
}

/** 先逐个上传待上传文件；任一个失败即中止，保留已上传成功的 id 与剩余待上传列表。 */
async function uploadPending():Promise<boolean>{
  if(!pendingFiles.value.length)return true
  // 循环前快照本轮队列：上传在途时列表被追加文件也不会被这一轮按新长度重读吞掉
  const queue=pendingFiles.value
  const uploaded:number[]=[]
  for(let index=0;index<queue.length;index++){
    const file=queue[index]
    uploadProgress.value=`正在上传 ${index+1}/${queue.length}`
    try{
      const created=await upload<Attachment>('/attachments',uploadBody(file))
      uploaded.push(created.id)
      fileOptions.value.push({id:created.id,label:`${created.title}（v${created.version}）`})
    }catch(e){
      form.attachmentIds=[...form.attachmentIds,...uploaded]
      pendingFiles.value=pendingFiles.value.slice(index)
      if(uploaded.length)emit('files-changed')
      ElMessage.error(`「${file.name}」上传失败：${(e as Error).message}`)
      return false
    }
  }
  form.attachmentIds=[...form.attachmentIds,...uploaded]
  pendingFiles.value=[]
  emit('files-changed')
  return true
}

async function submit(){
  if(saving.value)return
  if(!form.name.trim()){ElMessage.warning('请填写交付物名称');return}
  if(form.attachmentIds.length+pendingFiles.value.length>MAX_LINKED_FILES){ElMessage.warning(`一条交付记录最多关联 ${MAX_LINKED_FILES} 个文件`);return}
  saving.value=true
  try{
    // 全部新文件上传成功后再提交交付物，失败时弹窗保持打开可继续重试
    if(!await uploadPending())return
    // 上传期间弹窗可能已被遮罩/ESC/× 关闭：关掉就不再写库
    if(!dialog.value)return
    // 编辑时显式传 null 表示清空负责人；attachmentIds 传空数组即清空关联，不传则保留原有关联
    const payload:{name:string;status:DeliverableStatus;ownerId:number|null;remark:string;attachmentIds?:number[]}={name:form.name.trim(),status:form.status,ownerId:form.ownerId??null,remark:form.remark.trim()}
    // 已完成/已取消的工作项只能改状态，不传文件关联由服务端保留原值
    if(!props.nodeClosed)payload.attachmentIds=form.attachmentIds
    if(editingId.value)await api('PUT',`/deliverables/${editingId.value}`,payload)
    else await api('POST',`/nodes/${props.nodeId}/deliverables`,payload)
    dialog.value=false
    ElMessage.success(editingId.value?'交付物已更新':'交付物已新增')
    emit('refresh')
  }catch(e){ElMessage.error((e as Error).message)}finally{saving.value=false;uploadProgress.value=''}
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
        <h2>交付要求</h2>
        <p class="muted">记录约定需要提供的图纸、文档和测试资料；一条要求可关联本工作项的多个文件，也可在弹窗里直接上传。资料数量不换算成功能进度。</p>
      </div>
      <div class="deliverable-actions">
        <el-tag :type="total&&provided===total?'success':'info'">已提供 {{provided}}/{{total}}</el-tag>
        <el-button v-if="canCreate" type="primary" plain @click="openCreate">新增交付要求</el-button>
      </div>
    </div>
    <el-progress v-if="total" :percentage="providedPercent" :stroke-width="8" :show-text="false" style="margin:0 0 12px" />
    <el-table :data="items" empty-text="尚未约定交付资料，可直接在下方上传文件">
      <el-table-column prop="name" label="交付要求" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.status==='PROVIDED'?'success':'info'" size="small">{{scope.row.status==='PROVIDED'?'已提供':'待提供'}}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="负责人" width="110">
        <template #default="scope">{{scope.row.ownerName||'—'}}</template>
      </el-table-column>
      <el-table-column label="关联文件" min-width="220">
        <template #default="scope">
          <div v-if="linkedFiles(scope.row).length" class="linked-files">
            <span v-for="file in shownFiles(scope.row)" :key="file.id" class="linked-file">
              <span>{{ fileName(file) }}</span>
              <el-tag v-if="file.deleted===1" size="small" type="warning">已删除</el-tag>
            </span>
            <el-tag v-if="linkedFiles(scope.row).length>SHOWN_FILE_TAGS" size="small" type="info">+{{ linkedFiles(scope.row).length-SHOWN_FILE_TAGS }}</el-tag>
          </div>
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

    <el-dialog v-model="dialog" :title="editingId?'编辑交付物':'新增交付物'" width="560" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
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
        <el-form-item label="负责人">
          <el-select v-model="form.ownerId" clearable :disabled="nodeClosed" placeholder="未指定" style="width:100%">
            <el-option v-for="user in memberOptions" :key="user.id" :label="user.name" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联本工作项文件">
          <el-select v-model="form.attachmentIds" multiple clearable collapse-tags collapse-tags-tooltip :multiple-limit="MAX_LINKED_FILES" :disabled="fileLocked||saving" placeholder="可不关联，最多 20 个" style="width:100%">
            <el-option v-for="option in fileOptions" :key="option.id" :label="option.label" :value="option.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="上传文件">
          <el-upload :auto-upload="false" multiple :show-file-list="false" :disabled="fileLocked||saving" :on-change="onFilesPicked">
            <el-button :disabled="fileLocked||saving">选择本地文件</el-button>
            <template #tip><div class="muted upload-tip">可一次选多个文件，单个不超过 50MB；提交时先逐个上传，全部成功后再保存交付物。</div></template>
          </el-upload>
          <div v-if="pendingFiles.length" class="pending-files">
            <span v-for="(file,index) in pendingFiles" :key="`${index}-${file.name}`" class="pending-file">
              <span>{{ file.name }}</span>
              <el-button link type="danger" :disabled="saving" @click="removePending(index)">移除</el-button>
            </span>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="500" :disabled="nodeClosed" placeholder="资料位置、版本说明或注意事项" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span v-if="uploadProgress" class="muted upload-progress">{{ uploadProgress }}</span>
        <el-button :disabled="saving" @click="dialog=false">取消</el-button>
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
.linked-files{display:flex;flex-wrap:wrap;align-items:center;gap:6px}
.linked-file{display:inline-flex;align-items:center;gap:4px;padding:1px 8px;border:1px solid var(--line);border-radius:6px;background:#f8fafc;font-size:12px;overflow-wrap:anywhere}
.upload-tip{font-size:12px;margin-top:6px}
.upload-progress{margin-right:12px;font-size:13px}
.pending-files{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px;width:100%}
.pending-file{display:inline-flex;align-items:center;gap:4px;padding:2px 8px;border:1px solid var(--line);border-radius:6px;font-size:12px}
</style>
