<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import ModalDialog from '../components/ModalDialog.vue'
import { createManagedService, deleteManagedService, listManagedServices, testManagedService, updateManagedService, type ManagedService, type ManagedServiceInput } from '../api/services'

const services = ref<ManagedService[]>([])
const loading = ref(false); const busy = ref(false); const error = ref(''); const message = ref('')
const editing = ref<ManagedService | null | undefined>(undefined)
const form = reactive<ManagedServiceInput>({ name: '', systemName: '', environment: 'PRODUCTION', baseUrl: '', healthPath: '/actuator/health', metricsPath: '/actuator/metrics/{metric}', logsPath: null, dependenciesPath: '/actuator/health', operationsPath: null, bearerTokenEnv: null, enabled: true })

async function load(): Promise<void> { loading.value = true; try { services.value = await listManagedServices() } catch { error.value = '接入服务加载失败' } finally { loading.value = false } }
function open(item?: ManagedService): void {
  editing.value = item ?? null
  Object.assign(form, item ? { name: item.name, systemName: item.systemName, environment: item.environment, baseUrl: item.baseUrl, healthPath: item.healthPath, metricsPath: item.metricsPath, logsPath: item.logsPath, dependenciesPath: item.dependenciesPath, operationsPath: item.operationsPath, bearerTokenEnv: item.bearerTokenEnv, enabled: item.enabled } : { name: '', systemName: '', environment: 'PRODUCTION', baseUrl: '', healthPath: '/actuator/health', metricsPath: '/actuator/metrics/{metric}', logsPath: null, dependenciesPath: '/actuator/health', operationsPath: null, bearerTokenEnv: null, enabled: true })
}
async function save(): Promise<void> {
  busy.value = true; error.value = ''
  try { if (editing.value) await updateManagedService(editing.value.id, form); else await createManagedService(form); editing.value = undefined; await load() }
  catch { error.value = '保存失败：请检查服务名是否重复、URL 与路径格式是否正确' } finally { busy.value = false }
}
async function test(item: ManagedService): Promise<void> {
  error.value = ''; message.value = ''
  try { const result = await testManagedService(item.id); message.value = `${item.name}：${result.status} · ${result.summary}` }
  catch { error.value = `${item.name} 连接失败，请确认容器网络、端点和凭证环境变量` }
}
async function remove(item: ManagedService): Promise<void> {
  if (!window.confirm(`确认移除服务“${item.name}”？历史工单不会删除。`)) return
  try { await deleteManagedService(item.id); await load() } catch { error.value = '移除失败' }
}
onMounted(() => void load())
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">业务系统接入</p><h2>服务与可观测端点</h2><p class="header-copy">将工单中的目标服务映射到真实应用。密钥只保存为环境变量名，不写入数据库。</p></div><button class="primary-button" @click="open()">＋ 接入服务</button></header>
  <p v-if="error" class="error-message">{{ error }}</p><p v-if="message" class="success-message">{{ message }}</p>
  <section class="surface-panel data-list">
    <div v-for="item in services" :key="item.id" class="data-row service-row">
      <span class="health-orb compact" :data-status="item.enabled ? 'UP' : 'DOWN'" />
      <div class="row-main"><strong>{{ item.name }}</strong><small>{{ item.systemName }} · {{ item.environment }} · {{ item.baseUrl }}</small></div>
      <span class="status-pill" :data-status="item.enabled ? 'UP' : 'DOWN'">{{ item.enabled ? '已启用' : '已停用' }}</span>
      <button class="text-button" @click="test(item)">连通测试</button><button class="text-button" @click="open(item)">编辑</button><button class="danger-ghost" @click="remove(item)">移除</button>
    </div>
    <p v-if="loading" class="empty-state">正在加载…</p><p v-else-if="!services.length" class="empty-state large">尚未接入真实服务。仍可使用内置沙箱或创建仅依赖工单与知识库的诊断任务。</p>
  </section>
  <section class="surface-panel content-section integration-contract"><div class="section-heading"><div><p class="section-label">接入约定</p><h3>平台如何读取与执行</h3></div></div><p>健康端点兼容 Spring Boot Actuator。指标路径可使用 <code>{metric}</code> 占位符；日志端点返回 JSON 数组；依赖端点兼容 Actuator components。变更端点只有在人工审批后才会收到 POST 请求。</p></section>
  <ModalDialog v-if="editing !== undefined" :title="editing ? '编辑接入服务' : '接入真实服务'" eyebrow="系统配置" @close="editing = undefined">
    <form class="form-stack" @submit.prevent="save">
      <div class="form-grid"><label><span>服务唯一名</span><input v-model="form.name" maxlength="128" placeholder="order-service" required></label><label><span>所属系统</span><input v-model="form.systemName" maxlength="128" placeholder="交易系统" required></label></div>
      <div class="form-grid"><label><span>环境</span><select v-model="form.environment"><option>PRODUCTION</option><option>STAGING</option><option>TEST</option><option>DEVELOPMENT</option></select></label><label><span>Base URL</span><input v-model="form.baseUrl" placeholder="http://host.docker.internal:8081" required></label></div>
      <label><span>健康检查路径</span><input v-model="form.healthPath" required></label>
      <div class="form-grid"><label><span>指标路径（可选）</span><input v-model="form.metricsPath" placeholder="/actuator/metrics/{metric}"></label><label><span>日志路径（可选）</span><input v-model="form.logsPath" placeholder="/ops/logs"></label></div>
      <div class="form-grid"><label><span>依赖路径（可选）</span><input v-model="form.dependenciesPath" placeholder="/actuator/health"></label><label><span>变更路径（可选）</span><input v-model="form.operationsPath" placeholder="/ops/operations"></label></div>
      <label><span>Bearer Token 环境变量名（可选）</span><input v-model="form.bearerTokenEnv" placeholder="ORDER_SERVICE_TOKEN"><small>这里只填写变量名；实际 Token 配置在 server 容器环境变量中。</small></label>
      <label class="checkbox-row"><input v-model="form.enabled" type="checkbox"><span>启用该服务接入</span></label>
      <div class="modal-actions"><button type="button" class="text-button" @click="editing = undefined">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '保存接入' }}</button></div>
    </form>
  </ModalDialog>
</ConsoleLayout></template>
