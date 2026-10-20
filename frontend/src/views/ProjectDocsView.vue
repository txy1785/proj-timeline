<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { downloadAttachment, formatBytes, previewKind } from '../utils/attachment'
import type { Attachment, Timeline, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import AttachmentPanel from '../components/AttachmentPanel.vue'
import AttachmentPreview from '../components/AttachmentPreview.vue'
import { useAttachmentPreview } from '../composables/useAttachmentPreview'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectId = Number(route.params.id)
const timeline = ref<Timeline>()
const members = ref<User[]>([])
const loading = ref(true)
const tab = ref('all')
const files = ref<Attachment[]>([])
const sourceType = ref('')
const { previewOpen, previewItem, openPreview } = useAttachmentPreview()
const membership = computed(() => members.value.find(item => item.id === auth.user?.id))
const canManage = computed(() => !auth.isViewer && (auth.isAdmin || membership.value?.projectRole === 'PM'))
const visibleFiles = computed(() => sourceType.value ? files.value.filter(item => item.ownerType === sourceType.value) : files.value)
const SOURCES = [
  { value: '', label: '全部来源', tag: '' },
  { value: 'PROJECT', label: '项目级', tag: '项目' },
  { value: 'MODULE', label: '模块级', tag: '模块' },
  { value: 'NODE', label: '节点级', tag: '节点' },
  { value: 'TASK', label: '任务级', tag: '任务' }
]
const ownerTag = (type: string) => SOURCES.find(item => item.value === type)?.tag || type

async function loadFiles() {
  try {
    files.value = await api<Attachment[]>('GET', `/projects/${projectId}/attachments`)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function load() {
  loading.value = true
  try {
    timeline.value = await api<Timeline>('GET', `/projects/${projectId}/timeline`)
    members.value = await api<User[]>('GET', `/projects/${projectId}/members`)
    files.value = await api<Attachment[]>('GET', `/projects/${projectId}/attachments`)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

// 在「项目级文档」里上传后切回汇总页时刷新，避免看到过期列表
watch(tab, (value) => { if (value === 'all') void loadFiles() })

async function save(row: Attachment) {
  try {
    await downloadAttachment(row)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}


const size = formatBytes

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <header class="page-head">
      <div>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="`/projects`">项目</el-breadcrumb-item>
          <el-breadcrumb-item :to="`/projects/${projectId}/timeline`">{{ timeline?.name || '项目' }}</el-breadcrumb-item>
          <el-breadcrumb-item>项目文档</el-breadcrumb-item>
        </el-breadcrumb>
        <h1 style="margin-top:14px">项目文档</h1>
        <p>项目、模块、节点与任务的全部文件，按分类归档并可追溯历史版本。</p>
      </div>
      <el-button @click="router.push(`/projects/${projectId}/timeline`)">返回时间线</el-button>
    </header>

    <el-tabs v-model="tab">
      <el-tab-pane label="全部文件" name="all">
        <section class="surface">
          <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:16px">
            <div>
              <h2>全项目文件</h2>
              <p class="muted">项目文档、各模块文档、节点文件与任务附件都汇总在这里，共 {{ visibleFiles.length }} 个。</p>
            </div>
            <el-select v-model="sourceType" size="small" style="width:140px">
              <el-option v-for="source in SOURCES" :key="source.value" :label="source.label" :value="source.value" />
            </el-select>
          </div>
          <el-table :data="visibleFiles" empty-text="暂无文件">
            <el-table-column prop="title" label="标题" min-width="180">
              <template #default="scope">
                {{ scope.row.title }}
                <el-tag size="small" type="info" style="margin-left:6px">v{{ scope.row.version }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="归属" min-width="170">
              <template #default="scope">
                <el-tag size="small" :type="scope.row.ownerType === 'PROJECT' ? 'primary' : 'info'">{{ ownerTag(scope.row.ownerType) }}</el-tag>
                <span style="margin-left:6px">{{ scope.row.ownerName || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="categoryName" label="分类" width="140" />
            <el-table-column prop="originalName" label="文件名" min-width="160" show-overflow-tooltip />
            <el-table-column label="大小" width="95"><template #default="scope">{{ size(scope.row.sizeBytes) }}</template></el-table-column>
            <el-table-column prop="uploaderName" label="上传人" width="105" />
            <el-table-column prop="createdAt" label="上传时间" width="170" />
            <el-table-column label="操作" width="120">
              <template #default="scope">
                <el-button v-if="previewKind(scope.row.originalName) !== 'none'" link type="primary" @click="openPreview(scope.row)">预览</el-button>
                <el-button link type="primary" @click="save(scope.row)">下载</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="项目级文档" name="project" lazy>
        <el-alert v-if="timeline && !canManage" title="只有项目经理可以上传和删除项目文档，你可以查看和下载" type="info" :closable="false" style="margin-bottom:16px" />
        <section class="surface">
          <AttachmentPanel
            owner-type="PROJECT"
            :owner-id="projectId"
            :can-manage="canManage"
            :archived="timeline?.status === 'ARCHIVED'"
          />
        </section>
      </el-tab-pane>
    </el-tabs>

    <AttachmentPreview v-model="previewOpen" :attachment="previewItem" />
  </div>
</template>
