<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
onMounted(() => {
  if (!auth.user) void auth.loadCurrentUser()
})

async function logout(): Promise<void> {
  auth.clearSession()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <main class="console-shell">
    <aside class="console-sidebar">
      <div>
        <p class="eyebrow">OPS AGENT</p>
        <h1>Control Plane</h1>
      </div>
      <nav aria-label="主导航">
        <RouterLink to="/">系统概览</RouterLink>
        <RouterLink to="/tickets">工单与 Agent</RouterLink>
        <RouterLink to="/approvals">人工审批</RouterLink>
        <RouterLink to="/knowledge">知识库</RouterLink>
        <RouterLink to="/evaluations">评测中心</RouterLink>
      </nav>
      <div class="sidebar-footer">
        <small>{{ auth.user?.username ?? '正在加载' }} · Tenant {{ auth.user?.tenantId ?? '—' }}</small>
        <button class="secondary-button" type="button" @click="logout">退出登录</button>
      </div>
    </aside>
    <section class="console-content"><slot /></section>
  </main>
</template>
