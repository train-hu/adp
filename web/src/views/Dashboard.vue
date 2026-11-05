<template>
  <Layout>
    <div class="dashboard">
    <!-- 概览卡片 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <div class="card">
          <div class="card-label">服务器</div>
          <div class="card-value">{{ stats.serverTotal ?? '-' }}</div>
          <div class="card-sub">已纳管目标机</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="card">
          <div class="card-label">近 30 天部署</div>
          <div class="card-value">
            {{ stats.deploymentTotal30d ?? '-' }}
            <span class="card-value-sub">次</span>
          </div>
          <div class="card-sub">
            成功 {{ stats.deploymentSuccess30d ?? 0 }} 次
            <template v-if="stats.deploymentTotal30d">
              · 成功率 {{ successRate }}%
            </template>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="card">
          <div class="card-label">运行中服务</div>
          <div class="card-value">
            {{ stats.instanceRunning ?? '-' }}
            <span class="card-value-sub">/ {{ stats.instanceTotal ?? 0 }}</span>
          </div>
          <div class="card-sub">实例总数 {{ stats.instanceTotal ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="card">
          <div class="card-label">未运行服务</div>
          <div class="card-value" :class="{ warn: (stats.instanceStopped ?? 0) > 0 }">
            {{ stats.instanceStopped ?? '-' }}
          </div>
          <div class="card-sub">STOPPED / 异常</div>
        </div>
      </el-col>
    </el-row>

    <!-- 最近失败部署 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">最近异常部署</span>
        <el-button link type="primary" @click="$router.push('/deployments')">查看全部 →</el-button>
      </div>
      <el-table v-if="(stats.recentFailures || []).length" :data="stats.recentFailures" size="default">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="projectName" label="项目" min-width="140" />
        <el-table-column prop="serverId" label="服务器" width="90" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="错误摘要" min-width="220">
          <template #default="{ row }">
            <span class="err-text">{{ row.errorSummary || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结束时间" width="150">
          <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="近期无异常部署" :image-size="64" />
    </div>
    </div>
  </Layout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import Layout from '../components/Layout.vue'
import { getDashboard } from '../api/services'

const stats = ref({})

const successRate = computed(() => {
  const total = stats.value.deploymentTotal30d
  const ok = stats.value.deploymentSuccess30d
  if (!total) return 0
  return Math.round((ok / total) * 100)
})

function statusTag(status) {
  return { SUCCESS: 'success', FAILED: 'danger', ROLLED_BACK: 'warning', NEEDS_ATTENTION: 'warning', CANCELLED: 'info' }[status] || 'info'
}

function formatTime(t) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

onMounted(async () => {
  try {
    stats.value = await getDashboard()
  } catch { /* 401 等由 http 拦截器统一处理 */ }
})
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 18px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
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
.card-value.warn {
  color: var(--el-color-danger);
}
.card-value-sub {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-left: 2px;
}
.card-sub {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.panel {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 6px 16px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
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
.err-text {
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
</style>
