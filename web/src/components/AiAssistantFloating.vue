<template>
  <!-- 全局 AI 助手浮窗：登录后右下角圆形图标，点击弹出居中对话框 -->
  <div v-if="loggedIn" class="ai-floating">
    <div
      class="ai-fab"
      title="AI 助手"
      @click="visible = true"
    >
      <!-- AI 助手图标：四角星芒 + 小星点（替代原 ChatDotRound 聊天气泡） -->
      <svg viewBox="0 0 48 48" width="30" height="30" aria-hidden="true">
        <path d="M24 6 L28.5 19.5 L42 24 L28.5 28.5 L24 42 L19.5 28.5 L6 24 L19.5 19.5 Z" fill="#ffffff" />
        <path d="M36.5 7 L38 11.6 L42.5 13 L38 14.4 L36.5 19 L35 14.4 L30.5 13 L35 11.6 Z" fill="#ffffff" opacity="0.85" />
      </svg>
    </div>

    <el-dialog
      v-model="visible"
      title="AI 助手"
      width="840px"
      top="8vh"
      align-center
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
      class="ai-assistant-dialog"
    >
      <AiAssistant v-if="visible" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getToken } from '../api/http'
import AiAssistant from '../views/AiAssistant.vue'

// 登录态：getToken() 读 sessionStorage 是非响应式的，SPA 内登录后 loggedIn 不会重算。
// 借助 http.js 在 setSession/clearSession 后 dispatch 的 'auth-change' 事件递增 authTick，
// 让 loggedIn 依赖 authTick 触发重新计算，登录后浮窗图标即时显示（无需刷新页面）。
const authTick = ref(0)
const loggedIn = computed(() => {
  void authTick.value
  return !!getToken()
})
const onAuthChange = () => {
  authTick.value++
}
onMounted(() => window.addEventListener('auth-change', onAuthChange))
onBeforeUnmount(() => window.removeEventListener('auth-change', onAuthChange))

const visible = ref(false)
</script>

<style scoped>
.ai-floating {
  position: fixed;
  right: 28px;
  bottom: 28px;
  z-index: 2000;
}

.ai-fab {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  font-size: 26px;
  color: #fff;
  background: linear-gradient(135deg, #34d399 0%, #10b981 50%, #059669 100%);
  box-shadow: 0 6px 18px rgba(14, 159, 110, 0.4);
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.ai-fab:hover {
  transform: translateY(-4px) scale(1.06);
  box-shadow: 0 10px 24px rgba(14, 159, 110, 0.5);
}

.ai-assistant-dialog :deep(.chat-wrap) {
  height: 560px;
}
</style>