<script setup lang="ts">
import { computed } from 'vue'
import type { ScheduleProgress } from '../utils/progress'
import { scheduleVerdict } from '../utils/progress'

const props = defineProps<{ progress: ScheduleProgress; projectId: number; today: string }>()
const verdict = computed(() => scheduleVerdict(props.progress))
</script>

<template>
  <section class="surface progress-summary" aria-label="项目进度">
    <div class="progress-headline">
      <el-tag :type="verdict.tone" effect="dark" size="large">{{ verdict.label }}</el-tag>
      <p v-if="!progress.total" class="muted">现有工作项均已取消，新建工作项后这里会对照计划显示进度。</p>
      <template v-else>
        <p v-if="today && progress.due">
          已过计划完成日 <strong>{{ progress.due }}</strong> 项：已交付 <strong>{{ progress.dueDone }}</strong> 项<template v-if="progress.behind">，延期 <strong class="danger">{{ progress.behind }}</strong> 项</template>
        </p>
        <p v-else-if="today">还没有过计划完成日的工作项。</p>
        <p class="muted">全部 {{ progress.total }} 项已交付 {{ progress.done }} 项
          <RouterLink v-if="progress.behind" class="danger" :to="`/projects/${projectId}/work-items?risk=overdue`">查看延期 →</RouterLink>
        </p>
      </template>
    </div>
    <div v-if="progress.total" class="progress-bars">
      <div><span>交付进度</span><el-progress :percentage="progress.donePercent" :stroke-width="10" color="#218262" aria-label="交付进度" /></div>
      <div v-if="progress.timePercent !== undefined"><span>时间进度</span><el-progress :percentage="progress.timePercent" :stroke-width="10" color="#8c9bb3" aria-label="时间进度" /></div>
    </div>
  </section>
</template>

<style scoped>
.progress-headline{display:flex;align-items:center;gap:8px 16px;flex-wrap:wrap}.progress-headline p{margin:0;font-size:15px}
.progress-headline p.muted{font-size:13px}.progress-headline strong{font-size:18px}.progress-headline a{margin-left:10px}
.progress-bars{display:grid;grid-template-columns:1fr 1fr;gap:12px 32px;margin-top:18px}.progress-bars>div{display:grid;grid-template-columns:64px 1fr;align-items:center;gap:10px;font-size:13px;color:var(--muted)}
@media(max-width:600px){.progress-bars{grid-template-columns:1fr}}
</style>
