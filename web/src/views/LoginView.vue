<script setup lang="ts">
import axios from 'axios'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({
  tenantCode: '',
  username: '',
  password: '',
})
const submitting = ref(false)
const errorMessage = ref('')

async function submit(): Promise<void> {
  errorMessage.value = ''
  submitting.value = true
  try {
    await auth.login(form)
    const redirect = typeof route.query.redirect === 'string'
      ? route.query.redirect
      : '/'
    await router.replace(redirect)
  } catch (error: unknown) {
    errorMessage.value = axios.isAxiosError(error) && error.response?.status === 401
      ? '租户、用户名或密码错误'
      : '登录服务暂时不可用，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="brand-panel" aria-label="平台介绍">
      <p class="eyebrow">INTELLIGENT OPERATIONS</p>
      <h1>智能运维<br />Agent 平台</h1>
      <p class="brand-copy">
        让故障诊断、工具执行、人工审批与处置复盘形成可审计的完整闭环。
      </p>
      <div class="signal-card">
        <span class="signal-dot" />
        <span>平台服务已就绪</span>
      </div>
    </section>

    <section class="login-panel">
      <form class="login-card" @submit.prevent="submit">
        <header>
          <p class="section-label">安全访问</p>
          <h2>登录控制台</h2>
          <p>使用租户账号进入独立的运维工作区</p>
        </header>

        <label>
          <span>租户编码</span>
          <input
            v-model.trim="form.tenantCode"
            data-test="tenant-code"
            name="tenantCode"
            autocomplete="organization"
            required
          />
        </label>
        <label>
          <span>用户名</span>
          <input
            v-model.trim="form.username"
            data-test="username"
            name="username"
            autocomplete="username"
            required
          />
        </label>
        <label>
          <span>密码</span>
          <input
            v-model="form.password"
            data-test="password"
            name="password"
            type="password"
            autocomplete="current-password"
            required
          />
        </label>

        <p v-if="errorMessage" class="error-message" role="alert">
          {{ errorMessage }}
        </p>
        <button type="submit" :disabled="submitting">
          {{ submitting ? '正在验证…' : '进入控制台' }}
        </button>
      </form>
    </section>
  </main>
</template>
