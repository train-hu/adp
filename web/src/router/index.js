import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../api/http'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '仪表盘' } },
  { path: '/servers', name: 'servers', component: () => import('../views/Servers.vue'), meta: { title: '服务器管理' } },
  { path: '/databases', name: 'databases', component: () => import('../views/Databases.vue'), meta: { title: '数据库管理' } },
  { path: '/projects', name: 'projects', component: () => import('../views/Projects.vue'), meta: { title: '项目与部署包' } },
  { path: '/deployments', name: 'deployments', component: () => import('../views/Deployments.vue'), meta: { title: '部署任务' } },
  { path: '/instances', name: 'instances', component: () => import('../views/Instances.vue'), meta: { title: '服务管理' } },
  { path: '/documents', name: 'documents', component: () => import('../views/Documents.vue'), meta: { title: '文档中心' } },
  { path: '/llm-models', name: 'llm-models', component: () => import('../views/LlmModels.vue'), meta: { title: 'LLM 模型配置' } },
  { path: '/ai-assistant', name: 'ai-assistant', component: () => import('../views/AiAssistant.vue'), meta: { title: 'AI 助手' } },
  { path: '/monitor', name: 'monitor', component: () => import('../views/Monitor.vue'), meta: { title: '监控指标' } },
  { path: '/webhooks', name: 'webhooks', component: () => import('../views/Webhooks.vue'), meta: { title: 'Webhook 告警' } },
  { path: '/users', name: 'users', component: () => import('../views/Users.vue'), meta: { title: '用户管理' } },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 登录守卫：未登录一律进 /login（页面内按角色控制按钮）
router.beforeEach((to) => {
  if (to.name !== 'login' && !getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && getToken()) {
    return { path: '/' }
  }
  return true
})

export default router
