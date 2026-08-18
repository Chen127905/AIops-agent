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
      <div class="sidebar-main">
        <div class="sidebar-brand">
          <span class="brand-mark">OA</span>
          <div><p class="eyebrow">OPS AGENT</p><h1>智能运维平台</h1></div>
        </div>
        <nav aria-label="主导航">
          <p>工作台</p>
          <RouterLink to="/"><span class="nav-icon">⌂</span>系统概览</RouterLink>
          <RouterLink to="/tickets"><span class="nav-icon">◇</span>工单与 Agent</RouterLink>
          <RouterLink to="/approvals"><span class="nav-icon">✓</span>人工审批</RouterLink>
          <p>能力管理</p>
          <RouterLink v-if="auth.user?.roles.includes('ADMIN')" to="/services"><span class="nav-icon">⌁</span>业务系统接入</RouterLink>
          <RouterLink to="/knowledge"><span class="nav-icon">▤</span>知识库</RouterLink>
          <RouterLink to="/scenarios"><span class="nav-icon">⌘</span>场景目录</RouterLink>
          <RouterLink to="/evaluations"><span class="nav-icon">◎</span>评测中心</RouterLink>
          <RouterLink to="/system"><span class="nav-icon">◉</span>平台运行状态</RouterLink>
        </nav>
      </div>
      <div class="sidebar-footer">
        <div class="user-avatar">{{ (auth.user?.username ?? 'U').slice(0, 1).toUpperCase() }}</div>
        <div class="user-meta"><strong>{{ auth.user?.username ?? '正在加载' }}</strong><small>租户 {{ auth.user?.tenantId ?? '—' }}</small></div>
        <button class="logout-button" type="button" title="退出登录" @click="logout">↪</button>
      </div>
    </aside>
    <section class="console-content"><slot /></section>
  </main>
</template>
