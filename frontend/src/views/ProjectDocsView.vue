<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { Timeline, User } from '../api/types'
import { useAuthStore } from '../stores/auth'
import AttachmentPanel from '../components/AttachmentPanel.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectId = Number(route.params.id)
const timeline = ref<Timeline>()
const members = ref<User[]>([])
const loading = ref(true)
const membership = computed(() => members.value.find(item => item.id === auth.user?.id))
const canManage = computed(() => !auth.isViewer && (auth.isAdmin || membership.value?.projectRole === 'PM'))

async function load() {
  loading.value = true
  try {
    timeline.value = await api<Timeline>('GET', `/projects/${projectId}/timeline`)
    members.value = await api<User[]>('GET', `/projects/${projectId}/members`)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

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
        <p>立项、需求、设计、测试、运维与验收文档，按分类归档并可追溯历史版本。</p>
      </div>
      <el-button @click="router.push(`/projects/${projectId}/timeline`)">返回时间线</el-button>
    </header>
    <el-alert v-if="timeline && !canManage" title="只有项目经理可以上传和删除项目文档，你可以查看和下载" type="info" :closable="false" style="margin-bottom:18px" />
    <section class="surface">
      <AttachmentPanel
        owner-type="PROJECT"
        :owner-id="projectId"
        :can-manage="canManage"
        :archived="timeline?.status === 'ARCHIVED'"
      />
    </section>
  </div>
</template>
