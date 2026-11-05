<template>
  <Layout>
    <div class="dashboard" v-loading="loading">
      <!-- 概览卡片 -->
      <el-row :gutter="16">
        <el-col :xs="12" :sm="12" :md="6">
          <div class="card">
            <div class="card-label">近 30 天部署</div>
            <div class="card-value">{{ stats.deploymentTotal30d ?? 0 }}<span class="card-value-sub">次</span></div>
            <div class="card-sub">
              成功 {{ stats.deploymentSuccess30d ?? 0 }} · 失败 {{ stats.deploymentFailed30d ?? 0 }}
              <template v-if="stats.deploymentTotal30d"> · 成功率 {{ successRate }}%</template>
            </div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="card">
            <div class="card-label">平均部署耗时</div>
            <div class="card-value">{{ stats.avgDurationSeconds != null ? fmtDuration(stats.avgDurationSeconds) : '-' }}</div>
            <div class="card-sub">近 30 天终态部署</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="card">
            <div class="card-label">运行中服务</div>
            <div class="card-value">{{ stats.instanceRunning ?? 0 }}<span class="card-value-sub">/ {{ stats.instanceTotal ?? 0 }}</span></div>
            <div class="card-sub" :class="{ warn: (stats.instanceStopped ?? 0) > 0 }">
              {{ (stats.instanceStopped ?? 0) > 0 ? `未运行 ${stats.instanceStopped}` : '全部运行中' }}
            </div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="card">
            <div class="card-label">HTTP 请求</div>
            <div class="card-value">{{ stats.http?.totalRequests ?? 0 }}<span class="card-value-sub">次</span></div>
            <div class="card-sub" :class="{ warn: (stats.http?.serverErrors ?? 0) > 0 }">
              5xx {{ stats.http?.serverErrors ?? 0 }} 次
              <template v-if="stats.http?.totalRequests"> · 错误率 {{ errRate }}%</template>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 部署趋势 + JVM/HTTP -->
      <el-row :gutter="16">
        <el-col :xs="24" :md="14">
          <div class="panel">
            <div class="panel-head">
              <span class="panel-title">近 30 天部署趋势</span>
            </div>
            <el-table v-if="(stats.trend || []).length" :data="stats.trend" size="small" max-height="320">
              <el-table-column prop="day" label="日期" width="90" />
              <el-table-column prop="total" label="部署数" width="90" />
              <el-table-column label="成功" width="80">
                <template #default="{ row }"><span class="ok-text">{{ row.success }}</span></template>
              </el-table-column>
              <el-table-column label="失败" width="80">
                <template #default="{ row }"><span :class="{ 'err-text': row.failed > 0 }">{{ row.failed }}</span></template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="近期无部署记录" :image-size="64" />
          </div>
        </el-col>
        <el-col :xs="24" :md="10">
          <div class="panel">
            <div class="panel-head">
              <span class="panel-title">后端进程（JVM）</span>
            </div>
            <div class="metric-grid">
              <div class="metric">
                <div class="metric-label">堆内存</div>
                <div class="metric-value">{{ fmtBytes(stats.jvm?.heapUsedBytes) }}
                  <span class="metric-sub">/ {{ fmtBytes(stats.jvm?.heapMaxBytes) }}</span>
                </div>
                <el-progress
                  v-if="heapPercent != null"
                  :percentage="heapPercent"
                  :stroke-width="6"
                  :show-text="false"
                  :color="heapColor"
                />
              </div>
              <div class="metric">
                <div class="metric-label">非堆内存</div>
                <div class="metric-value">{{ fmtBytes(stats.jvm?.nonHeapUsedBytes) }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">线程数</div>
                <div class="metric-value">{{ stats.jvm?.threadCount ?? '-' }}
                  <span class="metric-sub">峰值 {{ stats.jvm?.peakThreadCount ?? '-' }}</span>
                </div>
              </div>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 服务实例健康 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">服务实例健康（{{ (stats.instances || []).length }}）</span>
        </div>
        <el-table v-if="(stats.instances || []).length" :data="stats.instances" size="small" max-height="420">
          <el-table-column prop="name" label="实例" min-width="180" show-overflow-tooltip />
          <el-table-column label="目标机" width="140">
            <template #default="{ row }">{{ row.serverIp || ('#' + row.serverId) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="instanceStatus(row.status).type" size="small">{{ instanceStatus(row.status).label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="在线" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.online" type="success" size="small" effect="plain">生效</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="部署时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.deployedAt) }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无服务实例" :image-size="64" />
      </div>

      <div class="refresh-bar">
        <el-icon class="is-loading" v-if="loading"><Loading /></el-icon>
        <span>数据每 30 秒自动刷新 · <el-button link type="primary" :disabled="loading" @click="load">立即刷新</el-button></span>
      </div>
    </div>
  </Layout>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Layout from '../components/Layout.vue'
import { getMonitorSummary } from '../api/services'
import { fmtTime, instanceStatus } from '../utils/status'

const loading = ref(false)
const stats = ref({})
let pollTimer = null

const successRate = computed(() => {
  const total = stats.value.deploymentTotal30d
  if (!total) return 0
  return Math.round(((stats.value.deploymentSuccess30d || 0) / total) * 100)
})
const errRate = computed(() => {
  const total = stats.value.http?.totalRequests
  if (!total) return 0
  return Math.round(((stats.value.http?.serverErrors || 0) / total) * 1000) / 10
})
const heapPercent = computed(() => {
  const used = stats.value.jvm?.heapUsedBytes
  const max = stats.value.jvm?.heapMaxBytes
  if (!used || !max || max <= 0) return null
  return Math.min(100, Math.round((used / max) * 100))
})
const heapColor = computed(() => {
  const p = heapPercent.value
  if (p == null) return ''
  if (p >= 85) return 'var(--el-color-danger)'
  if (p >= 70) return 'var(--el-color-warning)'
  return 'var(--el-color-primary)'
})

function fmtBytes(bytes) {
  if (bytes == null || bytes <= 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

function fmtDuration(seconds) {
  if (seconds == null) return '-'
  const s = Math.round(seconds)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rest = s % 60
  return rest ? `${m}m ${rest}s` : `${m}m`
}

async function load() {
  loading.value = true
  try {
    stats.value = await getMonitorSummary()
  } catch {
    // 401 等由 http 拦截器统一处理
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  pollTimer = setInterval(load, 30000)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 200px;
}
.card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 18px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  height: 100%;
}
.card-label {
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--el-text-color-secondary);
}
.card-value {
  margin-top: 8px;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 30px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  line-height: 1.1;
}
.card-value-sub {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-left: 3px;
}
.card-sub {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.card-sub.warn {
  color: var(--el-color-danger);
}
.panel {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 6px 16px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  height: 100%;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0 8px;
}
.panel-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--el-text-color-primary);
}
.ok-text { color: var(--el-color-success); font-weight: 600; }
.err-text { color: var(--el-color-danger); font-weight: 600; }
.muted { color: var(--el-text-color-placeholder); }
.metric-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0;
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.metric-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.metric-value {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 19px;
  font-weight: 700;
  color: var(--el-text-color-primary);
}
.metric-sub {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-placeholder);
}
.refresh-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  padding: 0 4px;
}
</style>
