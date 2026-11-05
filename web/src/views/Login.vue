<template>
  <div class="login-page">
    <!-- 左：品牌叙事区 -->
    <div class="brand-panel">
      <div class="grid-bg" />
      <div class="radar">
        <span class="ring r1" />
        <span class="ring r2" />
        <span class="ring r3" />
        <span class="sweep" />
        <span class="core" />
      </div>

      <div class="brand-block anim-fade-up">
        <div class="brand-mark">
          <svg viewBox="0 0 64 64" width="34" height="34" aria-hidden="true">
            <defs>
              <linearGradient id="loginbrandg" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#22d3ee" />
                <stop offset="1" stop-color="#0e9f6e" />
              </linearGradient>
            </defs>
            <path d="M32 8 L50 24 L42 24 L42 40 L37 40 L37 54 L27 54 L27 40 L22 40 L22 24 L14 24 Z" fill="url(#loginbrandg)" />
            <circle cx="32" cy="21" r="3.2" fill="#0a1220" />
          </svg>
        </div>
        <div class="brand-name">AutoDeploy</div>
      </div>

      <div class="story">
        <h1 class="story-title anim-fade-up" style="animation-delay: 0.1s">
          把每一次部署，<br />变成一次可靠的起飞。
        </h1>
        <p class="story-desc anim-fade-up" style="animation-delay: 0.2s">
          部署包管理 · 类型识别 · 一键发布 · 自动验证
        </p>

        <ul class="story-points">
          <li class="anim-fade-up" style="animation-delay: 0.3s">
            <el-icon class="pt-icon"><Promotion /></el-icon>
            <div>
              <strong>全流程部署</strong>
              <span>进程 / Nginx / Docker 多模式自动发布</span>
            </div>
          </li>
          <li class="anim-fade-up" style="animation-delay: 0.4s">
            <el-icon class="pt-icon"><Search /></el-icon>
            <div>
              <strong>智能类型识别</strong>
              <span>上传即识别技术栈，给出构建与启动建议</span>
            </div>
          </li>
          <li class="anim-fade-up" style="animation-delay: 0.5s">
            <el-icon class="pt-icon"><Cpu /></el-icon>
            <div>
              <strong>Git 直连拉取</strong>
              <span>填写仓库地址自动打包冻结 commit 版本</span>
            </div>
          </li>
        </ul>
      </div>

      <div class="brand-foot">Ops Console · v0.1</div>
    </div>

    <!-- 右：登录卡 -->
    <div class="form-panel">
      <div class="login-card anim-fade-up">
        <h2 class="card-title">欢迎回来</h2>
        <p class="card-sub">登录以进入部署控制台</p>

        <el-form :model="form" label-width="0" @submit.prevent="onLogin">
          <div class="field">
            <label>用户名</label>
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              size="large"
              autofocus
              @keyup.enter="onLogin"
            >
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </div>
          <div class="field">
            <label>密码</label>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              size="large"
              show-password
              @keyup.enter="onLogin"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </div>
          <el-button
            class="login-btn"
            type="primary"
            size="large"
            :loading="loading"
            @click="onLogin"
          >
            登 录
          </el-button>
        </el-form>

        
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { setSession, toastError } from '../api/http'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function onLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await http.post('/auth/login', {
      username: form.username.trim(),
      password: form.password,
    })
    setSession(data.token, data.username, data.role)
    ElMessage.success(`欢迎，${data.username}`)
    router.push(route.query.redirect || '/')
  } catch (err) {
    // 登录失败（401）：不清 token、不触发全局跳转，直接提示
    toastError(err)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.15fr 1fr;
}
@media (max-width: 960px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .brand-panel {
    display: none;
  }
}

/* ================= 品牌面板 ================= */
.brand-panel {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 56px;
  color: #fff;
  background:
    radial-gradient(140% 80% at 0% 100%, rgba(14, 159, 110, 0.16), transparent 55%),
    radial-gradient(100% 60% at 100% 0%, rgba(34, 211, 238, 0.12), transparent 50%),
    linear-gradient(160deg, #0a1220 0%, #0c1a2c 55%, #0a1424 100%);
}
.grid-bg {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse 90% 80% at 50% 45%, #000 20%, transparent 75%);
  pointer-events: none;
}

/* 雷达 */
.radar {
  position: absolute;
  top: -140px;
  right: -140px;
  width: 460px;
  height: 460px;
  pointer-events: none;
}
.radar .ring {
  position: absolute;
  inset: 0;
  margin: auto;
  border-radius: 50%;
  border: 1px solid rgba(34, 211, 238, 0.25);
}
.radar .r2 {
  transform: scale(0.68);
  border-color: rgba(34, 211, 238, 0.18);
}
.radar .r3 {
  transform: scale(0.38);
  border-color: rgba(34, 211, 238, 0.14);
}
.radar .ring {
  animation: ping 4s cubic-bezier(0, 0, 0.2, 1) infinite;
}
.radar .r2 {
  animation-delay: 1.3s;
}
.radar .r3 {
  animation-delay: 2.6s;
}
@keyframes ping {
  0% { transform: scale(0.3); opacity: 0.9; }
  80%, 100% { transform: scale(1.05); opacity: 0; }
}
.radar .sweep {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: conic-gradient(from 0deg, rgba(14, 159, 110, 0.28), transparent 22%);
  animation: radar-sweep 6s linear infinite;
}
.radar .core {
  position: absolute;
  inset: 0;
  margin: auto;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #22d3ee;
  box-shadow: 0 0 24px 6px rgba(34, 211, 238, 0.55);
}

/* 品牌 */
.brand-block {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
}
.brand-mark {
  width: 56px;
  height: 56px;
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  display: grid;
  place-items: center;
  box-shadow: 0 0 0 5px rgba(14, 159, 110, 0.08), 0 10px 30px -8px rgba(0, 0, 0, 0.5);
}
.brand-name {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

/* 叙事 */
.story {
  position: relative;
  max-width: 520px;
}
.story-title {
  font-family: 'Space Grotesk', 'Manrope', 'PingFang SC', sans-serif;
  font-size: 40px;
  line-height: 1.22;
  font-weight: 700;
  letter-spacing: -0.015em;
  margin: 0;
  background: linear-gradient(100deg, #ffffff 30%, #a7f3d0 75%, #67e8f9 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.story-desc {
  margin: 16px 0 0;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.55);
  letter-spacing: 0.02em;
}
.story-points {
  list-style: none;
  margin: 40px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.story-points li {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}
.pt-icon {
  font-size: 20px;
  color: #34d399;
  margin-top: 2px;
  filter: drop-shadow(0 0 8px rgba(52, 211, 153, 0.5));
}
.story-points strong {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.92);
}
.story-points span {
  display: block;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.45);
  margin-top: 2px;
}

.brand-foot {
  position: relative;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.3);
}

/* ================= 登录卡 ================= */
.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background:
    radial-gradient(700px 400px at 100% 0%, rgba(14, 159, 110, 0.06), transparent 60%),
    #f6f8fa;
}
.login-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 20px;
  padding: 40px 36px 32px;
  box-shadow: 0 24px 64px -20px rgba(16, 24, 40, 0.18), 0 4px 16px -6px rgba(16, 24, 40, 0.08);
}
.card-title {
  font-family: 'Space Grotesk', 'Manrope', sans-serif;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.01em;
  margin: 0;
}
.card-sub {
  margin: 8px 0 28px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.field {
  margin-bottom: 18px;
}
.field label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.login-btn {
  width: 100%;
  margin-top: 6px;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.1em;
}
</style>
