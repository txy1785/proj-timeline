<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
const week=ref(''); const data=ref<{weekStart:string;weekEnd:string;people:Record<string,any>[]}>()
async function load(){try{data.value=await api('GET','/workload',undefined,week.value?{week:week.value}:undefined)}catch(e){ElMessage.error((e as Error).message)}} onMounted(load)
</script>
<template><div><header class="page-head"><div><h1>人员负载</h1><p>{{data?.weekStart}} 至 {{data?.weekEnd}}，汇总当前账号可读取的项目。</p></div><el-date-picker v-model="week" value-format="YYYY-MM-DD" placeholder="选择一周中的任意日期" @change="load"/></header><section class="surface"><el-table :data="data?.people||[]"><el-table-column prop="name" label="人员" fixed/><el-table-column prop="activeNodeCount" label="进行中工作项"/><el-table-column prop="overdueNodeCount" label="延期工作项"/><el-table-column prop="openTaskCount" label="未完成任务"/><el-table-column prop="weekDueCount" label="本周到期"/><el-table-column prop="activeEstimateDays" label="全部未完成人天"/><el-table-column prop="weekDueEstimateDays" label="本周计划人天"/></el-table></section></div></template>
