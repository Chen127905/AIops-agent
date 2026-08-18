import { createRouter, createWebHistory } from 'vue-router'

import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'
import HomeView from '../views/HomeView.vue'
import LoginView from '../views/LoginView.vue'
import ApprovalListView from '../views/ApprovalListView.vue'
import EvaluationView from '../views/EvaluationView.vue'
import KnowledgeView from '../views/KnowledgeView.vue'
import TicketDetailView from '../views/TicketDetailView.vue'
import TicketListView from '../views/TicketListView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { public: true },
    },
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    { path: '/tickets', name: 'tickets', component: TicketListView },
    { path: '/tickets/:id', name: 'ticket-detail', component: TicketDetailView },
    { path: '/approvals', name: 'approvals', component: ApprovalListView },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
    { path: '/evaluations', name: 'evaluations', component: EvaluationView },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore(pinia)
  if (!to.meta.public && !auth.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.authenticated) {
    return { name: 'home' }
  }
  return true
})

export default router
