<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

onMounted(() => {
  void auth.loadCurrentUser()
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
        <a class="active" href="#overview">系统概览</a>
        <span>工单中心 · 待接入</span>
        <span>Agent 任务 · 待接入</span>
      </nav>
      <button class="secondary-button" type="button" @click="logout">
        退出登录
      </button>
    </aside>

    <section class="console-content" id="overview">
      <header class="console-header">
        <div>
          <p class="section-label">FOUNDATION STATUS</p>
          <h2>平台基础边界已就绪</h2>
        </div>
        <div class="user-chip">
          <span>{{ auth.user?.username ?? '正在加载' }}</span>
          <small>Tenant {{ auth.user?.tenantId ?? '—' }}</small>
        </div>
      </header>

      <div class="status-grid">
        <article>
          <span>01</span>
          <h3>Tenant Security</h3>
          <p>JWT 身份、角色与服务端租户上下文</p>
        </article>
        <article>
          <span>02</span>
          <h3>Model Gateway</h3>
          <p>Qwen 与 DeepSeek 的供应商中立调用边界</p>
        </article>
        <article>
          <span>03</span>
          <h3>Data Foundation</h3>
          <p>MySQL 业务状态与 pgvector 知识数据</p>
        </article>
      </div>
    </section>
  </main>
</template>
