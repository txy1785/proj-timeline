<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useProjectContext } from '../composables/useProjectContext'
import { workItemCounts } from '../utils/workItem'
import ModuleFormDialog from '../components/ModuleFormDialog.vue'

const projectId = Number(useRoute().params.id)
const { project, members, loading, error, isPm, refresh } = useProjectContext(projectId)
const moduleDialog = ref(false)
const active = computed(() => project.value?.status === 'ACTIVE')
const pmId = computed(() => members.value.find(member => member.projectRole === 'PM')?.id)
const projectLevelCount = computed(() => workItemCounts(project.value?.projectWorkItems || []))
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link type="primary" @click="refresh">重新加载</el-button></el-alert>
    <template v-if="project">
      <header class="page-head">
        <div><h1>功能模块</h1><p>按项目的功能区域组织工作。进入模块后查看小功能、负责人和过程文件。</p></div>
        <div class="page-actions"><el-button v-if="isPm && active" type="primary" @click="moduleDialog = true">新建模块</el-button><RouterLink :to="`/projects/${projectId}/work-items`"><el-button>查看全部工作项</el-button></RouterLink></div>
      </header>
      <section v-if="!project.modules.length" class="surface empty">
        <h2>还没有功能模块</h2>
        <p>模块是功能分组。先建立模块，再在模块里安排需要交付的小功能。</p>
        <el-button v-if="isPm && active" type="primary" @click="moduleDialog = true">建立首个模块</el-button>
      </section>
      <div v-else class="module-grid">
        <section v-for="module in project.modules" :key="module.id" class="surface module-card">
          <div class="module-card-heading"><h2><RouterLink :to="`/projects/${projectId}/modules/${module.id}`">{{ module.name }}</RouterLink></h2><span class="module-count">{{ workItemCounts(module.nodes || []).done }}/{{ workItemCounts(module.nodes || []).total }} 已交付</span></div>
          <p class="module-description">{{ module.description || '暂无模块说明' }}</p>
          <div class="module-card-footer"><span>负责人 {{ module.ownerName }} · {{ module.nodes?.length || 0 }} 个工作项</span><RouterLink :to="`/projects/${projectId}/modules/${module.id}`">进入模块 →</RouterLink></div>
        </section>
      </div>
      <section v-if="project.projectWorkItems.length" class="surface project-level">
        <div><h2>项目级工作项</h2><p>跨模块的测试、维护和上线保障直接归属项目。</p></div>
        <RouterLink :to="`/projects/${projectId}/work-items?moduleId=project`">{{ projectLevelCount.done }}/{{ projectLevelCount.total }} 已交付 · 查看工作项 →</RouterLink>
      </section>
      <ModuleFormDialog v-model="moduleDialog" :project-id="projectId" :members="members" :default-owner-id="pmId" @saved="refresh" />
    </template>
  </div>
</template>

<style scoped>
.module-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:18px;margin-bottom:20px}.module-card{display:flex;flex-direction:column;min-height:175px}.module-card-heading,.module-card-footer,.project-level{display:flex;justify-content:space-between;align-items:center;gap:16px}.module-card h2,.project-level h2{margin:0;font-size:19px}.module-card h2 a,.module-card-footer a,.project-level a{color:var(--blue)}.module-count{color:var(--muted);white-space:nowrap}.module-description{color:var(--muted);line-height:1.6;flex:1;white-space:pre-line}.module-card-footer{border-top:1px solid var(--line);padding-top:14px;font-size:14px}.module-card-footer span,.project-level p{color:var(--muted)}.project-level p{margin-bottom:0}.empty p{color:var(--muted)}
@media(max-width:600px){.module-card-heading,.module-card-footer,.project-level{align-items:flex-start;flex-direction:column}}
</style>
