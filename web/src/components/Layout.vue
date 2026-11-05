<template>
  <el-container class="layout">
    <el-aside width="240px" class="aside">
      <!-- 品牌 -->
      <div class="brand">
        <div class="brand-mark">
          <svg viewBox="0 0 64 64" width="30" height="30" aria-hidden="true">
            <defs>
              <linearGradient id="brandg" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#22d3ee" />
                <stop offset="1" stop-color="#0e9f6e" />
              </linearGradient>
            </defs>
            <path d="M32 8 L50 24 L42 24 L42 40 L37 40 L37 54 L27 54 L27 40 L22 40 L22 24 L14 24 Z" fill="url(#brandg)" />
            <circle cx="32" cy="21" r="3.2" fill="#0a1220" />
          </svg>
        </div>
        <div class="brand-text">
          <div class="brand-name">AutoDeploy</div>
          <div class="brand-sub">自动化部署平台</div>
        </div>
      </div>

      <!-- 分组导航 -->
      <el-menu
        :default-active="$route.path"
        router
        class="nav"
        :collapse-transition="false"
      >
        <div class="nav-group-label">总览</div>
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon><span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/monitor">
          <el-icon><Odometer /></el-icon><span>监控指标</span>
        </el-menu-item>

        <div class="nav-group-label">资源</div>
        <el-menu-item index="/servers">
          <el-icon><Monitor /></el-icon><span>服务器管理</span>
        </el-menu-item>
        <el-menu-item index="/databases">
          <el-icon><Coin /></el-icon><span>数据库管理</span>
        </el-menu-item>

        <div class="nav-group-label">交付</div>
        <el-menu-item index="/projects">
          <el-icon><FolderOpened /></el-icon><span>项目与部署包</span>
        </el-menu-item>
        <el-menu-item index="/deployments">
          <el-icon><Promotion /></el-icon><span>部署任务</span>
        </el-menu-item>
        <el-menu-item index="/instances">
          <el-icon><Operation /></el-icon><span>服务管理</span>
        </el-menu-item>

        <div class="nav-group-label">其他</div>
        <el-menu-item index="/documents">
          <el-icon><Document /></el-icon><span>文档中心</span>
        </el-menu-item>
        <el-menu-item index="/llm-models">
          <el-icon><Cpu /></el-icon><span>LLM 模型</span>
        </el-menu-item>
        <el-menu-item v-if="canAdmin" index="/webhooks">
          <el-icon><Bell /></el-icon><span>Webhook 告警</span>
        </el-menu-item>
        <el-menu-item v-if="canAdmin" index="/users">
          <el-icon><User /></el-icon><span>用户管理</span>
        </el-menu-item>
      </el-menu>

      <div class="aside-footer">
        <span class="footer-dot" />
        <span class="footer-text">平台运行中</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="crumb">
          <span class="crumb-title">{{ $route.meta.title }}</span>
          <span class="crumb-env">Ops Console</span>
        </div>
        <div class="user-zone">
          <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99" class="bell-badge">
            <el-tooltip content="站内通知" placement="bottom">
              <el-button link class="bell-btn" @click="openNotifications">
                <el-icon :size="20"><Bell /></el-icon>
              </el-button>
            </el-tooltip>
          </el-badge>
          <el-tag :type="roleTagType" effect="dark" size="small" round>
            <span class="status-dot" />{{ role }}
          </el-tag>
          <div class="user-meta">
            <span class="username">{{ username }}</span>
          </div>
          <el-button link type="primary" @click="logout" class="logout-btn">
            <el-icon><SwitchButton /></el-icon>退出
          </el-button>
        </div>
      </el-header>

      <!-- 站内通知抽屉 -->
      <el-drawer v-model="drawerVisible" title="站内通知" size="380px" :append-to-body="true">
        <div class="notify-head">
          <span class="notify-head-text">未读 {{ notifications.length }} 条</span>
          <el-button link type="primary" size="small" :disabled="notifications.length === 0" @click="onReadAll">
            全部已读
          </el-button>
        </div>
        <el-empty v-if="notifications.length === 0" description="暂无未读通知" :image-size="72" />
        <ul v-else class="notify-list">
          <li v-for="n in notifications" :key="n.id" class="notify-item">
            <div class="notify-item-top">
              <el-tag size="small" :type="n.level === 'WARN' ? 'danger' : 'success'" effect="light">
                {{ n.level === 'WARN' ? '警告' : '信息' }}
              </el-tag>
              <span class="notify-title">{{ n.title }}</span>
            </div>
            <div v-if="n.message" class="notify-msg">{{ n.message }}</div>
            <div class="notify-time">{{ formatTime(n.createdAt) }}</div>
          </li>
        </ul>
      </el-drawer>
      <el-main class="main">
        <slot />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getUsername, getRole, clearSession, hasRole } from '../api/http'
import { listNotifications, getUnreadCount, readAllNotifications } from '../api/services'

const username = computed(() => getUsername())
const role = computed(() => getRole())
const roleTagType = computed(() => ({ ADMIN: 'danger', OPS: 'warning' }[role.value] || 'info'))
const canAdmin = computed(() => hasRole('ADMIN'))

function logout() {
  clearSession()
  location.href = '/login'
}

// ---- 站内通知（铃铛 + 抽屉，30s 轮询未读数） ----
const unreadCount = ref(0)
const drawerVisible = ref(false)
const notifications = ref([])
let pollTimer = null

async function refreshUnreadCount() {
  try {
    const res = await getUnreadCount()
    unreadCount.value = res?.count || 0
  } catch { /* 401 等由 http 拦截器统一处理 */ }
}

async function openNotifications() {
  try {
    const list = await listNotifications()
    notifications.value = list || []
  } catch { /* 同上 */ }
  drawerVisible.value = true
}

async function onReadAll() {
  await readAllNotifications()
  notifications.value = []
  unreadCount.value = 0
}

function formatTime(t) {
  if (!t) return ''
  return t.replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  refreshUnreadCount()
  pollTimer = setInterval(refreshUnreadCount, 30000)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.layout {
  height: 100vh;
}

/* ================= 侧边栏 ================= */
.aside {
  background:
    radial-gradient(120% 60% at 0% 0%, rgba(34, 211, 238, 0.1), transparent 55%),
    linear-gradient(180deg, #0a1220 0%, #0b1626 60%, #0a1220 100%);
  border-right: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}
.aside::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.025) 1px, transparent 1px);
  background-size: 28px 28px;
  mask-image: radial-gradient(80% 50% at 50% 0%, #000 0%, transparent 75%);
  pointer-events: none;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 20px 16px;
  position: relative;
}
.brand-mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  display: grid;
  place-items: center;
  box-shadow: 0 0 0 4px rgba(14, 159, 110, 0.08);
  flex-shrink: 0;
}
.brand-mark svg {
  filter: drop-shadow(0 2px 8px rgba(14, 159, 110, 0.4));
}
.brand-text {
  line-height: 1.2;
}
.brand-name {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.02em;
}
.brand-sub {
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.42);
  margin-top: 2px;
  letter-spacing: 0.04em;
}

/* 导航 */
.nav {
  border-right: none !important;
  background: transparent !important;
  flex: 1;
  padding: 8px 12px 0;
  overflow-y: auto;
  position: relative;
}
.nav::-webkit-scrollbar {
  width: 5px;
}
.nav::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.14);
  border-radius: 4px;
}
.nav-group-label {
  padding: 14px 14px 6px;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.32);
}
.nav-group-label:first-child {
  padding-top: 6px;
}
.aside :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
  margin: 3px 0;
  border-radius: 10px;
  color: rgba(255, 255, 255, 0.62);
  background: transparent;
  transition: all 0.2s cubic-bezier(0.22, 1, 0.36, 1);
  font-weight: 500;
}
.aside :deep(.el-menu-item .el-icon) {
  font-size: 17px;
  color: rgba(255, 255, 255, 0.5);
  transition: color 0.2s ease;
}
.aside :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}
.aside :deep(.el-menu-item:hover .el-icon) {
  color: rgba(255, 255, 255, 0.85);
}
.aside :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(14, 159, 110, 0.22), rgba(34, 211, 238, 0.1));
  color: #fff;
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(14, 159, 110, 0.35);
  position: relative;
}
.aside :deep(.el-menu-item.is-active .el-icon) {
  color: #22d3ee;
}
.aside :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 22px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, #22d3ee, #0e9f6e);
  box-shadow: 0 0 12px rgba(14, 159, 110, 0.7);
}

.aside-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 22px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
  position: relative;
}
.footer-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #16a34a;
  box-shadow: 0 0 0 0 rgba(22, 163, 74, 0.6);
  animation: live-pulse 2s infinite;
}
@keyframes live-pulse {
  0% { box-shadow: 0 0 0 0 rgba(22, 163, 74, 0.5); }
  70% { box-shadow: 0 0 0 7px rgba(22, 163, 74, 0); }
  100% { box-shadow: 0 0 0 0 rgba(22, 163, 74, 0); }
}

/* ================= 顶栏 ================= */
.header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--el-border-color-lighter);
  position: sticky;
  top: 0;
  z-index: 10;
}
.crumb {
  display: flex;
  align-items: baseline;
  gap: 14px;
}
.crumb-title {
  font-family: 'Space Grotesk', 'Manrope', sans-serif;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--el-text-color-primary);
}
.crumb-env {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--brand-primary);
  background: var(--el-color-primary-light-9);
  padding: 3px 10px;
  border-radius: 999px;
}
.user-zone {
  display: flex;
  align-items: center;
  gap: 14px;
}
.user-meta .username {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.logout-btn {
  padding: 6px 10px;
  border-radius: 8px;
  font-weight: 600;
}
.logout-btn:hover {
  background: var(--el-color-primary-light-9);
}

/* 通知铃铛 + 抽屉 */
.bell-badge {
  display: flex;
  align-items: center;
}
.bell-btn {
  padding: 6px 8px;
  border-radius: 8px;
  color: var(--el-text-color-regular);
}
.bell-btn:hover {
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
}
.notify-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.notify-head-text {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.notify-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.notify-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--el-bg-color);
}
.notify-item-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.notify-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.notify-msg {
  margin-top: 6px;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
.notify-time {
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}

/* ================= 内容区 ================= */
.main {
  background:
    radial-gradient(1000px 400px at 100% -10%, rgba(14, 159, 110, 0.05), transparent 60%),
    var(--el-bg-color-page);
  padding: 26px 28px;
  overflow-y: auto;
}
</style>
