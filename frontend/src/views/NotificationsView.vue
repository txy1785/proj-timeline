<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { Notification, PageResponse } from '../api/types'
import { notificationLabel } from '../utils/notification'
const router=useRouter(); const items=ref<Notification[]>([])
async function load(){try{items.value=(await api<PageResponse<Notification>>('GET','/notifications',undefined,{size:100})).list}catch(e){ElMessage.error((e as Error).message)}}
async function open(item:Notification){if(!item.readAt)await api('PUT',`/notifications/${item.id}/read`); if(item.link)router.push(item.link);else load()}
async function readAll(){await api('PUT','/notifications/read-all');await load()} onMounted(load)
</script>
<template><div><header class="page-head"><div><h1>通知中心</h1><p>项目风险、指派、完成、周报发布与变更审批提醒。</p></div><el-button @click="readAll">全部已读</el-button></header><section class="surface"><div v-for="item in items" :key="item.id" style="display:flex;gap:14px;padding:17px 4px;border-bottom:1px solid var(--line);cursor:pointer" @click="open(item)"><span class="status-dot" :style="{background:item.readAt?'#ccd3dd':'#2f6fed'}"></span><div><strong>{{item.title}}</strong><p class="muted" style="margin:5px 0">{{item.content||notificationLabel(item.type)}}</p><small class="muted">{{item.createdAt}}</small></div></div><div v-if="!items.length" class="empty">暂无通知</div></section></div></template>
