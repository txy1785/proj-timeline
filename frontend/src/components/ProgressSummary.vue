<script setup lang="ts">
import { computed } from 'vue'
import type { ScheduleProgress } from '../utils/progress'
import { scheduleVerdict } from '../utils/progress'

const props = defineProps<{ progress: ScheduleProgress; projectId: number }>()
const verdict = computed(() => scheduleVerdict(props.progress))
</script>

<template>
  <section class="surface progress-summary" aria-label="项目进度">
    <div class="progress-headline">
      <el-tag :type="verdict.tone" effect="dark" size="large">{{ verdict.label }}</el-tag>
      <p v-if="progress.total">
        按计划到今天应交付 <strong>{{ progress.expected }}</strong> 项，实际已交付 <strong>{{ progress.done }}</strong> 项（共 {{ progress.total }} 项）
        <RouterLink v-if="progress.behind" class="danger" :to="`/projects/${projectId}/work-items?risk=overdue`">查看 {{ progress.behind }} 项延期 →</RouterLink>
      </p>
      <p v-else class="muted">建立工作项并填写计划完成日期后，这里会对照计划显示进度。</p>
    </div>
    <div v-if="progress.total" class="progress-bars">
      <div><span>交付进度</span><el-progress :percentage="progress.donePercent" :stroke-width="10" color="#218262" /></div>
      <div v-if="progress.timePercent !== undefined"><span>时间进度</span><el-progress :percentage="progress.timePercent" :stroke-width="10" color="#8c9bb3" /></div>
    </div>
  </section>
</template>

<style scoped>
.progress-headline{display:flex;align-items:center;gap:16px;flex-wrap:wrap}.progress-headline p{margin:0;font-size:15px}
.progress-headline strong{font-size:18px}.progress-headline a{margin-left:10px}
.progress-bars{display:grid;grid-template-columns:1fr 1fr;gap:12px 32px;margin-top:18px}.progress-bars>div{display:grid;grid-template-columns:64px 1fr;align-items:center;gap:10px;font-size:13px;color:var(--muted)}
@media(max-width:600px){.progress-bars{grid-template-columns:1fr}}
</style>
