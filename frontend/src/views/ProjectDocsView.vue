<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { Attachment } from '../api/types'
import { useProjectContext } from '../composables/useProjectContext'
import { useAttachmentPreview } from '../composables/useAttachmentPreview'
import { downloadAttachment, formatBytes, previewKind } from '../utils/attachment'
import { formatDateTime } from '../utils/format'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import AttachmentPreview from '../components/AttachmentPreview.vue'

type LinkedAttachment = Attachment & { nodeId?: number }
const projectId = Number(useRoute().params.id)
const router = useRouter()
const { project, loading, error, isPm } = useProjectContext(projectId)
const tab = ref('all')
const files = ref<LinkedAttachment[]>([])
const sourceType = ref('')
const search = ref('')
const filesLoading = ref(true)
const filesError = ref('')
const canManage = computed(() => isPm.value && project.value?.status === 'ACTIVE')
const { previewOpen, previewItem, openPreview } = useAttachmentPreview()
const sources = [
  { value: '', label: '全部来源', tag: '' }, { value: 'PROJECT', label: '项目级', tag: '项目' },
  { value: 'MODULE', label: '模块级', tag: '模块' }, { value: 'NODE', label: '工作项级', tag: '工作项' }, { value: 'TASK', label: 'Bug及历史任务', tag: 'Bug/历史任务' }
]
const visibleFiles = computed(() => files.value.filter(file => (!sourceType.value || file.ownerType === sourceType.value)
  && (!search.value.trim() || `${file.title} ${file.originalName} ${file.ownerName || ''}`.toLowerCase().includes(search.value.trim().toLowerCase()))))
const ownerTag = (type: string) => sources.find(item => item.value === type)?.tag || type
async function loadFiles() {
  filesLoading.value = true
  filesError.value = ''
  try { files.value = await api<LinkedAttachment[]>('GET', `/projects/${projectId}/attachments`) }
  catch (e) { filesError.value = (e as Error).message }
  finally { filesLoading.value = false }
}
function openOwner(file: LinkedAttachment) {
  if (file.ownerType === 'MODULE') router.push(`/projects/${projectId}/modules/${file.ownerId}`)
  else if (file.ownerType === 'NODE') router.push(`/nodes/${file.ownerId}`)
  else if (file.ownerType === 'TASK' && file.nodeId) router.push(`/nodes/${file.nodeId}/tasks/${file.ownerId}`)
  else router.push(`/projects/${projectId}/${file.ownerType === 'PROJECT' ? 'overview' : 'work-items'}`)
}
async function save(file: Attachment) { try { await downloadAttachment(file) } catch (e) { ElMessage.error((e as Error).message) } }
watch(tab, value => { if (value === 'all') void loadFiles() })
onMounted(loadFiles)
</script>

<template>
  <div v-loading="loading">
    <header class="page-head"><div><h1>项目文档</h1><p>在一个位置查找项目、模块、功能工作项、Bug及历史任务的文件，保留来源与版本。</p></div><el-button v-if="canManage" type="primary" @click="tab = 'project'">上传项目文档</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-tabs v-if="project" v-model="tab">
      <el-tab-pane label="全部文件" name="all"><section v-loading="filesLoading" class="surface">
        <div class="files-toolbar"><div><h2>全项目文件 <small>{{ visibleFiles.length }}</small></h2><p class="muted">点击归属可返回对应工作位置；上传文件仍按各层负责人权限管理。</p></div><div class="file-filters"><el-input v-model="search" clearable placeholder="搜索标题、文件名或归属" aria-label="搜索文件" /><el-select v-model="sourceType" aria-label="文件来源"><el-option v-for="source in sources" :key="source.value" :label="source.label" :value="source.value" /></el-select></div></div>
        <el-alert v-if="filesError" :title="filesError" type="error" :closable="false"><el-button link type="primary" @click="loadFiles">重新加载文件</el-button></el-alert>
        <el-table v-else :data="visibleFiles" :empty-text="search || sourceType ? '当前筛选下没有文件' : '暂无文件，可在对应项目、模块、工作项或Bug中上传'">
          <el-table-column prop="title" label="标题" min-width="180"><template #default="scope">{{ scope.row.title }} <el-tag size="small" type="info">v{{ scope.row.version }}</el-tag></template></el-table-column>
          <el-table-column label="归属" min-width="190"><template #default="scope"><el-tag size="small" type="info">{{ ownerTag(scope.row.ownerType) }}</el-tag><el-button link type="primary" class="owner-link" @click="openOwner(scope.row)">{{ scope.row.ownerName || (scope.row.ownerType === 'PROJECT' ? project.name : '查看所属位置') }}</el-button><small v-if="scope.row.ownerType === 'TASK' && !scope.row.nodeId" class="muted">此文件缺少直接任务定位，可在工作项中查找。</small></template></el-table-column>
          <el-table-column prop="categoryName" label="分类" width="140" />
          <el-table-column prop="originalName" label="文件名" min-width="170" show-overflow-tooltip />
          <el-table-column label="大小" width="100"><template #default="scope">{{ formatBytes(scope.row.sizeBytes) }}</template></el-table-column>
          <el-table-column prop="uploaderName" label="上传人" width="100" />
          <el-table-column label="上传时间" width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column>
          <el-table-column label="操作" width="120" fixed="right"><template #default="scope"><el-button v-if="previewKind(scope.row.originalName) !== 'none'" link type="primary" @click="openPreview(scope.row)">预览</el-button><el-button link type="primary" @click="save(scope.row)">下载</el-button></template></el-table-column>
        </el-table>
        <div v-if="!filesError && (search || sourceType) && !visibleFiles.length" class="clear-files"><el-button link type="primary" @click="search = ''; sourceType = ''">清除筛选</el-button></div>
      </section></el-tab-pane>
      <el-tab-pane label="项目级文档" name="project" lazy><el-alert :title="canManage ? `当前上传位置：项目「${project.name}」。模块、工作项及Bug文件请进入对应位置上传。` : '项目级文档由项目经理上传，你可以查看和下载。'" type="info" :closable="false" class="section-gap" /><section class="surface"><AttachmentPanel owner-type="PROJECT" :owner-id="projectId" :can-manage="canManage" :archived="project.status === 'ARCHIVED'" /></section></el-tab-pane>
    </el-tabs>
    <AttachmentPreview v-model="previewOpen" :attachment="previewItem" />
  </div>
</template>

<style scoped>
.files-toolbar{display:flex;justify-content:space-between;align-items:center;gap:18px;margin-bottom:18px}.files-toolbar h2{margin:0}.files-toolbar small{font-size:14px;font-weight:400;color:var(--muted);margin-left:8px}.files-toolbar p{line-height:1.7;margin-bottom:0}.file-filters{display:flex;gap:12px;flex-wrap:wrap;justify-content:flex-end}.file-filters>.el-input{width:240px}.file-filters>.el-select{width:140px}.owner-link{display:block;margin:6px 0;white-space:normal;text-align:left;line-height:1.6}.section-gap{margin-bottom:18px}.clear-files{text-align:center;padding:12px}
@media(max-width:1000px){.files-toolbar{align-items:flex-start;flex-direction:column}.file-filters{justify-content:flex-start}}
@media(max-width:600px){.file-filters,.file-filters>.el-input,.file-filters>.el-select{width:100%}}
</style>