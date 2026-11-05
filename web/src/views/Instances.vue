<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>服务实例（{{ rows.length }}）</span>
          <el-button :icon="Refresh" circle size="small" @click="load" />
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="name" label="实例名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="projectName" label="项目" width="110" />
        <el-table-column label="服务器" width="110">
          <template #default="{ row }">{{ serverName(row.serverId) }}</template>
        </el-table-column>
        <el-table-column label="unit" min-width="160">
          <template #default="{ row }">
            <code v-if="row.unitName">{{ row.unitName }}</code>
            <el-tag v-else size="small" type="info">{{ modeLabel(row.mode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="port" label="端口" width="80" />
        <el-table-column label="访问地址" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <code v-if="accessUrl(row)">{{ accessUrl(row) }}</code>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="instanceStatus(row.status).type" size="small">
              {{ instanceStatus(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在线" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.online" type="success" size="small">是</el-tag>
            <el-tag v-else type="info" size="small">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="部署时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.deployedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canOps" link type="success" size="small" :disabled="row.status === 'RUNNING'"
                       :loading="row._acting === 'start'" @click="onAct(row, 'start')">
              启动
            </el-button>
            <el-button v-if="canOps" link type="warning" size="small" :disabled="row.status === 'STOPPED'"
                       :loading="row._acting === 'stop'" @click="onAct(row, 'stop')">
              停止
            </el-button>
            <el-button v-if="canOps" link type="primary" size="small"
                       :loading="row._acting === 'restart'" @click="onAct(row, 'restart')">
              重启
            </el-button>
            <el-button v-if="canOps" link type="danger" size="small"
                       :loading="row._acting === 'delete'" @click="onDelete(row)">
              删除
            </el-button>
            <el-button link type="primary" size="small" @click="openLogs(row)">
              实时日志
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 实时日志抽屉（SSE 流式，fetch + ReadableStream） -->
    <el-drawer v-model="logVisible" :title="`实时日志 —— ${logTarget?.name || ''}`" size="60%" @close="closeLogs">
      <div class="log-toolbar">
        <el-tag :type="logConnected ? 'success' : 'info'" size="small">
          {{ logConnected ? '连接中' : '已断开' }}
        </el-tag>
        <el-button size="small" :disabled="logConnected" @click="openLogs(logTarget)">重连</el-button>
        <el-button size="small" type="danger" :disabled="!logConnected" @click="closeLogs">停止</el-button>
      </div>
      <pre class="log-box" ref="logBoxRef">{{ logText || '（等待日志输出…）' }}</pre>
    </el-drawer>
  </Layout>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import Layout from '../components/Layout.vue'
import { hasRole, toastError } from '../api/http'
import {
  listServiceInstances, startInstance, stopInstance, restartInstance, deleteServiceInstance,
  streamLogs, listServers,
} from '../api/services'
import { instanceStatus, fmtTime } from '../utils/status'

const canOps = computed(() => hasRole('OPS'))
const loading = ref(false)
const rows = ref([])
const servers = ref([])

const logVisible = ref(false)
const logTarget = ref(null)
const logText = ref('')
const logConnected = ref(false)
const logBoxRef = ref(null)
let logController = null

function serverName(id) {
  return servers.value.find((s) => s.id === id)?.name || `#${id}`
}

// 服务访问地址：服务器 ip + 实例端口（端口未配置则不显示）
function accessUrl(row) {
  const server = servers.value.find((s) => s.id === row.serverId)
  if (!server?.ip || row.port == null) return ''
  return `http://${server.ip}:${row.port}`
}

function modeLabel(mode) {
  return { PROCESS: 'systemd', NGINX: 'nginx 托管', DOCKER: 'docker-compose' }[mode || 'PROCESS']
}

async function load() {
  loading.value = true
  try {
    const [inst, ss] = await Promise.all([listServiceInstances(), listServers()])
    rows.value = inst.map((r) => ({ ...r, _acting: null }))
    servers.value = ss
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function onAct(row, action) {
  row._acting = action
  try {
    if (action === 'start') await startInstance(row.id)
    else if (action === 'stop') await stopInstance(row.id)
    else await restartInstance(row.id)
    ElMessage.success(`${action === 'start' ? '启动' : action === 'stop' ? '停止' : '重启'}指令已下发`)
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    row._acting = null
  }
}

async function onDelete(row) {
  try {
    const warning = row.online
      ? '该实例当前在线（生效版本），删除将同时清理目标机上的服务资源（systemd unit / nginx 站点 / 容器）。'
      : '删除将移除该实例记录；当前在线实例的其他资源不受影响。'
    await ElMessageBox.confirm(
      `确认删除服务实例「${row.name}」？\n${warning}\n此操作不可恢复。`,
      '删除服务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  row._acting = 'delete'
  try {
    await deleteServiceInstance(row.id)
    ElMessage.success('服务实例已删除')
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    row._acting = null
  }
}

function openLogs(row) {
  if (row.mode === 'NGINX') {
    ElMessage.warning('nginx 托管实例无独立日志文件，站点日志见目标机 /var/log/nginx/（访问/错误日志）')
    return
  }
  closeLogs()
  logTarget.value = row
  logText.value = ''
  logVisible.value = true
  logController = streamLogs(
    row.id,
    (line) => {
      logText.value += line + '\n'
      // 限制内存：保留最近 5000 行
      const lines = logText.value.split('\n')
      if (lines.length > 5000) logText.value = lines.slice(-5000).join('\n')
      scheduleScroll()
    },
    // 流自然结束（后端 30 分钟超时 / journalctl 退出）或出错时置为已断开
    () => {
      logConnected.value = false
    },
  )
  logConnected.value = true
}

function closeLogs() {
  logController?.abort()
  logController = null
  logConnected.value = false
}

let scrollPending = false
function scheduleScroll() {
  if (scrollPending) return
  scrollPending = true
  nextTick(() => {
    const box = logBoxRef.value
    if (box) box.scrollTop = box.scrollHeight
    scrollPending = false
  })
}

onMounted(load)
onBeforeUnmount(closeLogs)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.log-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.log-box {
  height: calc(100vh - 180px);
  margin: 0;
  padding: 12px;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: Consolas, monospace;
  font-size: 12px;
  border-radius: 4px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
