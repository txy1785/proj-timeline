<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore(); const router = useRouter(); const route = useRoute(); const loading = ref(false)
const form = reactive({ username: '', password: '' })
async function submit() {
  if (!form.username || !form.password) return ElMessage.warning('请输入用户名和密码')
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    const requested = String(route.query.redirect || '')
    const target = requested.startsWith('/') && !requested.startsWith('//') && !requested.startsWith('/login')
      ? requested : auth.user?.globalRole === 'USER' ? '/work' : '/projects'
    await router.replace(target)
  }
  catch (error) { ElMessage.error((error as Error).message) }
  finally { loading.value = false }
}
</script>
<template>
  <div class="login-page">
    <section class="login-visual"><div class="brand"><span class="brand-mark">时</span>项目时间线</div><h1>知道下一步，推进每次交付。</h1><p>安排功能交付、提交资料、跟进 Bug，让团队清楚谁在什么时候完成什么。</p></section>
    <section class="login-form"><div class="login-card"><p class="muted">欢迎回来</p><h2>登录工作台</h2><el-form label-position="top" @submit.prevent="submit"><el-form-item label="用户名"><el-input v-model="form.username" autocomplete="username" size="large" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" size="large" @keyup.enter="submit" /></el-form-item><el-button type="primary" size="large" style="width:100%" :loading="loading" @click="submit">登录</el-button></el-form></div></section>
  </div>
</template>
