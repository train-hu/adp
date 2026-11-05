<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>部署任务（{{ rows.length }}）</span>
          <el-button v-if="canOps" type="primary" :icon="Plus" @click="openCreate">
            发起部署
          </el-button>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="项目" min-width="110">
          <template #default="{ row }">{{ projectName(row.projectId) }}</template>
        </el-table-column>
        <el-table-column label="版本" width="80">
          <template #default="{ row }">#{{ row.versionId }}</template>
        </el-table-column>
        <el-table-column label="目标服务器" min-width="110">
          <template #default="{ row }">{{ serverName(row.serverId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="deployStatus(row.status).type" size="small">
              {{ deployStatus(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startCmd" label="启动命令" min-width="160" show-overflow-tooltip />
        <el-table-column prop="errorSummary" label="错误摘要" min-width="140" show-overflow-tooltip />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row.id)">
              详情
            </el-button>
            <el-button
              v-if="canOps && row.status === 'PENDING'"
              link type="warning" size="small"
              @click="onCancel(row)"
            >
              取消
            </el-button>
            <el-button
              v-if="canOps && isDeployTerminal(row.status)"
              link type="success" size="small"
              @click="onRedeploy(row)"
            >
              重新部署
            </el-button>
            <el-button
              v-if="canOps && isDeployTerminal(row.status) && row.oldVersionId"
              link type="warning" size="small"
              :loading="rollbacking"
              @click="onRollback(row)"
            >
              回退
            </el-button>
            <el-button link type="primary" size="small" @click="goDocuments(row)">
              文档
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发起部署 -->
    <el-dialog v-model="createDialog" title="发起部署" width="560">
      <el-form :model="createForm" label-width="110px">
        <el-form-item label="项目" required>
          <el-select v-model="createForm.projectId" style="width: 100%" :disabled="!!prefillVersion"
                     @change="onProjectChange">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="createServices.length" label="服务" required>
          <el-select v-model="createForm.serviceId" style="width: 100%" @change="onServiceChange">
            <el-option
              v-for="s in createServices"
              :key="s.id"
              :label="s.name + (s.defaultService ? '（默认）' : '')"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="部署包版本" required>
          <el-select v-model="createForm.versionId" style="width: 100%" @change="onVersionChange">
            <el-option
              v-for="v in createVersions"
              :key="v.id"
              :label="`#${v.id} ${v.fileName}（${v.detectedType || '未知'}）`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标服务器" required>
          <el-select v-model="createForm.serverId" style="width: 100%">
            <el-option
              v-for="s in servers"
              :key="s.id"
              :label="`${s.name}（${s.ip}:${s.sshPort}）`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="部署方式" required>
          <el-select v-model="createForm.mode" style="width: 100%">
            <el-option label="进程（PROCESS）" value="PROCESS" />
            <el-option label="Nginx 静态站（NGINX）" value="NGINX" />
            <el-option label="Docker（DOCKER）" value="DOCKER" />
          </el-select>
        </el-form-item>
        <el-divider>
          以下可选（留空则取项目默认 → 识别建议）
          <el-tooltip v-if="startCmdExempt" content="NGINX / DOCKER 模式由平台接管启动，无需启动命令" placement="top">
            <el-tag size="small" type="success" style="margin-left: 6px">当前方式免启动命令</el-tag>
          </el-tooltip>
        </el-divider>
        <el-form-item v-if="!startCmdExempt" label="启动命令">
          <el-input v-model="createForm.startCmd" placeholder="如 java -jar app.jar" />
        </el-form-item>
        <el-form-item label="服务端口">
          <el-input-number v-model="createForm.port" :min="1" :max="65535" />
          <span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">留空按服务端口 → 项目默认端口 → 识别建议 → 自动分配</span>
        </el-form-item>
        <el-form-item label="健康检查路径">
          <el-input v-model="createForm.healthPath" placeholder="如 /health" />
        </el-form-item>
        <el-form-item v-if="createForm.mode !== 'NGINX'" label="数据库">
          <el-select v-model="createForm.databaseId" style="width: 100%" clearable placeholder="不使用数据库（清空即不选）">
            <el-option label="自动创建项目独立 MySQL 库" :value="-1" />
            <el-option
              v-for="db in databases"
              :key="db.id"
              :label="`${db.name}（${db.dbType} ${db.host}:${db.port}/${db.databaseName}）`"
              :value="db.id"
            />
          </el-select>
          <div class="form-hint">
            平台会把连接信息（DB_* / SPRING_DATASOURCE_*）注入应用环境变量；「自动创建」会在目标机起一个独立 MySQL 容器（端口自动避让）
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button
          v-if="canOps"
          :icon="MagicStick"
          :disabled="!createForm.versionId || !createForm.serverId"
          :loading="aiGenerating"
          @click="onAiGenerate"
        >
          AI 生成部署方案
        </el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">确认部署</el-button>
      </template>
    </el-dialog>

    <!-- AI 部署方案：展示 + 可编辑 + 确认执行 / 放弃 -->
    <el-dialog v-model="aiDialog" title="AI 部署方案" width="720" top="6vh">
      <div v-if="aiPlan">
        <el-alert
          :title="aiPlan.source === 'AI'
            ? 'AI（ReAct）已解析部署包并探测目标机，生成如下方案，请人工确认后执行'
            : 'LLM 不可用，已降级规则引擎按识别结果生成方案，请人工确认后执行'"
          :type="aiPlan.source === 'AI' ? 'success' : 'warning'"
          :closable="false"
          style="margin-bottom: 16px"
        />
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="项目">{{ aiPlan.projectName }}</el-descriptions-item>
          <el-descriptions-item label="部署包">{{ aiPlan.fileName }}</el-descriptions-item>
          <el-descriptions-item label="目标服务器">{{ aiPlan.serverName }}</el-descriptions-item>
          <el-descriptions-item label="方案来源">
            <el-tag :type="aiPlan.source === 'AI' ? 'success' : 'warning'" size="small">{{ aiPlan.source }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="识别类型">{{ aiPlan.detectedType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="部署模式">
            <el-select v-model="aiEdit.mode" size="small" style="width: 220px">
              <el-option label="进程（PROCESS）" value="PROCESS" />
              <el-option label="Nginx 静态站（NGINX）" value="NGINX" />
              <el-option label="Docker（DOCKER）" value="DOCKER" />
            </el-select>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">可微调参数（留空则用方案原值）</el-divider>
        <el-form label-width="110px">
          <el-form-item v-if="aiEdit.mode === 'PROCESS'" label="启动命令">
            <el-input v-model="aiEdit.startCmd" placeholder="如 java -jar app.jar" />
          </el-form-item>
          <el-form-item label="服务端口">
            <el-input-number v-model="aiEdit.port" :min="1" :max="65535" placeholder="可空" controls-position="right" style="width: 180px" />
          </el-form-item>
          <el-form-item label="健康检查路径">
              <el-input v-model="aiEdit.healthPath" placeholder="如 /health" />
            </el-form-item>
            <el-form-item v-if="aiEdit.mode !== 'NGINX'" label="数据库">
              <el-select v-model="aiEdit.databaseId" size="small" style="width: 100%" clearable placeholder="不使用数据库（清空即不选）">
                <el-option label="自动创建项目独立 MySQL 库" :value="-1" />
                <el-option
                  v-for="db in databases"
                  :key="db.id"
                  :label="`${db.name}（${db.dbType} ${db.host}:${db.port}/${db.databaseName}）`"
                  :value="db.id"
                />
              </el-select>
            </el-form-item>
          </el-form>

        <el-divider content-position="left">AI 推理说明</el-divider>
        <div class="ai-reasoning">{{ aiPlan.reasoning || '（无）' }}</div>

        <el-collapse style="margin-top: 12px">
          <el-collapse-item
            :title="aiEdit.mode !== aiPlan.mode
              ? '执行命令预览（模式已修改，建议重新生成方案以刷新命令预览）'
              : '执行命令预览（人工审阅，确认后由平台安全管线执行）'"
          >
            <el-alert
              v-if="aiEdit.mode !== aiPlan.mode"
              title="模式已修改，以下预览仍为原模式的命令，仅作参考"
              type="warning" :closable="false" style="margin-bottom: 8px"
            />
            <div class="ai-mode-dimmed" :class="{ 'ai-mode-dimmed-active': aiEdit.mode !== aiPlan.mode }">
              <div v-for="(cmd, label) in aiPlan.commands" :key="label" class="cmd-block">
                <div class="cmd-label">{{ label }}</div>
                <pre class="step-log">{{ cmd }}</pre>
              </div>
            </div>
          </el-collapse-item>
          <el-collapse-item title="AI 决策轨迹（ReAct 工具调用记录）">
            <div v-if="aiTrajectory.length">
              <div v-for="t in aiTrajectory" :key="t.step" class="traj-step">
                <el-tag size="small" type="info">{{ t.step }}. {{ t.tool }}</el-tag>
                <div v-if="t.result" class="step-log">{{ t.result }}</div>
              </div>
            </div>
            <el-empty v-else description="无轨迹（规则引擎降级）" :image-size="50" />
          </el-collapse-item>
        </el-collapse>
      </div>
      <el-empty v-else description="暂无方案" :image-size="60" />
      <template #footer>
        <el-button @click="aiDialog = false">关闭</el-button>
        <el-button
          v-if="aiPlan && aiPlan.status === 'DRAFT'"
          type="danger"
          plain
          :loading="aiAbandoning"
          @click="onAiAbandon"
        >
          放弃方案
        </el-button>
        <el-button
          v-if="aiPlan && aiPlan.status === 'DRAFT'"
          type="primary"
          :loading="aiConfirming"
          @click="onAiConfirm"
        >
          确认并执行部署
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉：状态 + 步骤时间线 -->
    <el-drawer v-model="detailVisible" :title="`部署 #${detail?.id || ''} 详情`" size="560px" @close="onDetailClose">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="状态">
            <el-tag :type="deployStatus(detail.status).type" size="small">
              {{ deployStatus(detail.status).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="模式">{{ detail.mode }}</el-descriptions-item>
          <el-descriptions-item label="启动命令" :span="2">{{ detail.startCmd || '-' }}</el-descriptions-item>
          <el-descriptions-item label="端口">{{ detail.port || '-' }}</el-descriptions-item>
          <el-descriptions-item label="健康路径">{{ detail.healthPath || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数据库">
            <template v-if="detail.databaseId === -1">自动创建项目独立库</template>
            <template v-else-if="detail.databaseId">{{ databaseName(detail.databaseId) }}</template>
            <template v-else>-</template>
          </el-descriptions-item>
          <el-descriptions-item label="部署目录" :span="2">{{ detail.workdir || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmtTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ fmtTime(detail.finishedAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.errorSummary" label="错误摘要" :span="2">
            <span style="color: #f56c6c">{{ detail.errorSummary }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="canOps && isDeployTerminal(detail.status)" style="margin-bottom: 16px">
          <el-button type="primary" :loading="redeploying" @click="onRedeploy(detail)">
            重新部署（按原参数新建一次）
          </el-button>
          <el-button
            v-if="detail.oldVersionId"
            type="warning" :loading="rollbacking" @click="onRollback(detail)"
          >
            回退到上一版本
          </el-button>
        </div>

        <div class="step-title">部署步骤</div>
        <el-timeline v-if="steps.length">
          <el-timeline-item
            v-for="step in steps"
            :key="step.id"
            :type="stepIconType(step.status)"
            :timestamp="fmtTime(step.finishedAt || step.startedAt)"
            placement="top"
          >
            <b>{{ step.name }}</b>
            <el-tag :type="stepStatus(step.status).type" size="small" style="margin-left: 8px">
              {{ stepStatus(step.status).label }}
            </el-tag>
            <div v-if="step.logText" class="step-log">{{ step.logText }}</div>
            <div v-if="stepDetail(step)" class="step-detail">{{ stepDetail(step) }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无步骤" :image-size="60" />
      </template>
    </el-drawer>
  </Layout>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, MagicStick } from '@element-plus/icons-vue'
import Layout from '../components/Layout.vue'
import { hasRole, toastError } from '../api/http'
import {
  listDeployments, getDeployment, getSteps, createDeployment, cancelDeployment, redeployDeployment, rollbackDeployment, checkPort,
  listProjects, listVersions, listServers, listDatabases,
  listSubServices,
  listServiceInstances,
  generateAiPlan, confirmAiPlan, abandonAiPlan,
} from '../api/services'
import { deployStatus, stepStatus, isDeployTerminal, fmtTime } from '../utils/status'

const route = useRoute()
const router = useRouter()
const canOps = computed(() => hasRole('OPS'))

const loading = ref(false)
const rows = ref([])
const projects = ref([])
const servers = ref([])
const databases = ref([])
const prefillVersion = ref(null)

const createDialog = ref(false)
const creating = ref(false)
const createForm = reactive({ projectId: null, serviceId: null, versionId: null, serverId: null, mode: 'PROCESS', startCmd: '', port: null, healthPath: '', databaseId: null })
const createVersions = ref([])
const createServices = ref([])

// ---- AI 部署方案（ReAct 生成 → 人工确认执行）----
const aiDialog = ref(false)
const aiPlan = ref(null)
const aiGenerating = ref(false)
const aiConfirming = ref(false)
const aiAbandoning = ref(false)
const aiEdit = reactive({ mode: '', startCmd: '', port: null, healthPath: '', databaseId: null })

const aiTrajectory = computed(() => {
  if (!aiPlan.value?.trajectoryJson) return []
  try {
    const arr = JSON.parse(aiPlan.value.trajectoryJson)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
})

const detailVisible = ref(false)
const detail = ref(null)
const steps = ref([])
let pollTimer = null

function projectName(id) {
  return projects.value.find((p) => p.id === id)?.name || `#${id}`
}
function serverName(id) {
  return servers.value.find((s) => s.id === id)?.name || `#${id}`
}
function databaseName(id) {
  return databases.value.find((d) => d.id === id)?.name || `#${id}`
}
function stepIconType(status) {
  return { SUCCESS: 'success', FAILED: 'danger', RUNNING: 'primary' }[status] || 'info'
}
function stepDetail(step) {
  if (!step.detail) return ''
  try {
    const obj = JSON.parse(step.detail)
    return typeof obj === 'string' ? obj : JSON.stringify(obj)
  } catch {
    return step.detail
  }
}

async function load() {
  loading.value = true
  try {
    const [deps, ps, ss, dbs] = await Promise.all([listDeployments(), listProjects(), listServers(), listDatabases()])
    rows.value = deps
    projects.value = ps
    servers.value = ss
    databases.value = dbs
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  Object.assign(createForm, { projectId: null, serviceId: null, versionId: null, serverId: null, mode: 'PROCESS', startCmd: '', port: null, healthPath: '', databaseId: null })
  createVersions.value = []
  createServices.value = []
  prefillVersion.value = null
  const qProject = Number(route.query.project) || null
  const qVersion = Number(route.query.version) || null
  if (qProject && projects.value.some((p) => p.id === qProject)) {
    createForm.projectId = qProject
    await onProjectChange(qProject)
    if (qVersion && createVersions.value.some((v) => v.id === qVersion)) {
      createForm.versionId = qVersion
      prefillVersion.value = qVersion
      onVersionChange(qVersion)
    }
  }
  createDialog.value = true
}

async function onProjectChange(projectId) {
  createForm.versionId = null
  createVersions.value = []
  createServices.value = []
  createForm.serviceId = null
  if (!projectId) return
  try {
    const project = projects.value.find((p) => p.id === projectId)
    createServices.value = await listSubServices(projectId)
    // 默认选中默认服务（单服务项目必有）；多服务项目取第一个，用户可切换
    const svc = createServices.value.find((s) => s.defaultService) || createServices.value[0] || null
    createForm.serviceId = svc ? svc.id : null
    // 预填：服务级 > 项目默认，用户可覆盖；留空由后端兜底链处理
    if (project) {
      createForm.startCmd = (svc && svc.startCmd) || project.defaultStartCmd || ''
      createForm.port = (svc && svc.port) || project.defaultPort || null
      createForm.healthPath = project.defaultHealthPath || ''
    }
    createVersions.value = svc ? await listVersions(projectId, svc.id) : []
  } catch (err) {
    toastError(err)
  }
}

async function onServiceChange(serviceId) {
  createForm.versionId = null
  createVersions.value = []
  if (!serviceId) return
  try {
    const project = projects.value.find((p) => p.id === createForm.projectId)
    const svc = createServices.value.find((s) => s.id === serviceId)
    createForm.startCmd = (svc && svc.startCmd) || project?.defaultStartCmd || ''
    createForm.port = (svc && svc.port) || project?.defaultPort || null
    createVersions.value = await listVersions(createForm.projectId, serviceId)
  } catch (err) {
    toastError(err)
  }
}

// 预填识别建议（第三级兜底），仅填充当前为空且版本确有建议的字段；并按包识别类型预选部署方式。
// 注意：端口不做前端识别建议预填——避免显式值压过服务级端口（服务级端口是 V21 后更精确的口径，由后端兜底链处理）
function onVersionChange(versionId) {
  const v = createVersions.value.find((x) => x.id === versionId)
  if (!v) return
  // 按包识别类型预选部署方式（STATIC_WEB→NGINX / DOCKER→DOCKER / 其余→PROCESS），用户可改
  createForm.mode = v.detectedType === 'STATIC_WEB' ? 'NGINX'
    : v.detectedType === 'DOCKER' ? 'DOCKER' : 'PROCESS'
  if (!v.detectedJson) return
  let d = null
  try {
    d = JSON.parse(v.detectedJson)
  } catch {
    return
  }
  if (!createForm.startCmd && d.suggestedStartCmd) createForm.startCmd = d.suggestedStartCmd
  if (!createForm.healthPath && d.suggestedHealthPath) createForm.healthPath = d.suggestedHealthPath
}

// 部署方式 NGINX/DOCKER 由平台接管启动，免启动命令 → 隐藏启动命令输入框。
const startCmdExempt = computed(() => createForm.mode === 'NGINX' || createForm.mode === 'DOCKER')

// ---- 升级场景：查 (project, server) 当前在线实例的端口 ----
// 升级部署时该端口必然被"本项目自身服务"占用，应复用而非强制切换（否则端口漂移、原端口被释放）。
// 查询失败（非 OPS 不可见 / 无实例）返回 null，不影响首次部署流程。
async function fetchOnlinePort(projectId, serverId, serviceId) {
  if (!projectId || !serverId) return null
  try {
    const list = await listServiceInstances({ projectId, serverId, serviceId })
    const online = list.find((i) => i.online && i.status === 'RUNNING')
    return online?.port || null
  } catch {
    return null
  }
}

// 选定项目+服务器后预填在线端口（升级时复用原端口；首次部署无在线实例则不预填，走默认逻辑）
watch([() => createForm.projectId, () => createForm.serverId, () => createForm.serviceId], async ([pid, sid, svcid]) => {
  if (!pid || !sid) return
  const onlinePort = await fetchOnlinePort(pid, sid, svcid || undefined)
  if (onlinePort) createForm.port = onlinePort
})

// ---- 端口预检（确认部署前 SSH 目标机探测；SSH 失败放行不阻断）----
// 返回 { proceed, port }：proceed=false 表示用户放弃/端点报错（调用方必须 return，
// 禁止回退到表单原值继续创建）；proceed=true 放行部署，port 为最终端口
// （null 表示保留原值、由后端兜底，不放行到探测起点端口）。
async function runPortCheck(serverId, port, onPortUpdated, ownPort) {
  // 起点端口：用户指定 > 服务级端口 > 项目默认 > 版本识别建议 > 8080
  const project = projects.value.find((p) => p.id === createForm.projectId)
  const svc = createServices.value.find((s) => s.id === createForm.serviceId)
  const version = createVersions.value.find((v) => v.id === createForm.versionId)
  let suggested = null
  if (version?.detectedJson) {
    try { suggested = JSON.parse(version.detectedJson).suggestedPort } catch { suggested = null }
  }
  const startPort = port || svc?.port || project?.defaultPort || suggested || 8080
  const userChosePort = !!port
  let r
  try {
    r = await checkPort({ serverId, port: startPort })
  } catch (err) {
    toastError(err)
    return { proceed: false, port: null }
  }
  if (!r.probed) {
    try {
      await ElMessageBox.confirm(
        '目标机端口探测失败，将直接创建部署（执行阶段仍有健康检查兜底）。继续？',
        '端口探测失败', { type: 'warning' })
    } catch {
      return { proceed: false, port: null }
    }
    // 放行：保留用户填的端口；未填则留空走后端兜底，绝不漂移成 startPort
    return { proceed: true, port: port || null }
  }
  if (!r.occupied) {
    if (userChosePort) return { proceed: true, port: startPort }
    // 未指定端口：自动分配探测到的空闲端口（起点本身空闲即起点）
    try {
      await ElMessageBox.confirm(`已自动分配端口 ${startPort}，确认以此部署？`, '自动分配端口', { type: 'info' })
    } catch {
      return { proceed: false, port: null }
    }
    onPortUpdated?.(startPort)
    return { proceed: true, port: startPort }
  }
  // 被占用：若占用端口正是本项目当前在线服务端口（ownPort）→ 升级场景，
  // 部署管线会以 restart/软链 reload 替换原进程，复用原端口即可，无需切换。
  if (ownPort && startPort === ownPort) {
    try {
      await ElMessageBox.confirm(
        `端口 ${startPort} 已被本项目当前在线服务占用（升级部署将替换原进程并复用该端口）。确认以此端口部署？`,
        '升级部署 · 复用端口', { type: 'info' })
    } catch {
      return { proceed: false, port: null }
    }
    onPortUpdated?.(startPort)
    return { proceed: true, port: startPort }
  }
  // 被占用（非自身服务）
  if (r.recommendedPort) {
    try {
      await ElMessageBox.confirm(
        `端口 ${startPort} 已被占用${r.processInfo ? '：' + r.processInfo : ''}。<br>是否换到推荐空闲端口 ${r.recommendedPort}？`,
        '端口被占用',
        { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '换到推荐端口', cancelButtonText: '放弃' })
    } catch {
      // 用户点「放弃」→ 明确中止，禁止回退到原被占端口创建
      return { proceed: false, port: null }
    }
    onPortUpdated?.(r.recommendedPort)
    return { proceed: true, port: r.recommendedPort }
  }
  // 未找到空闲端口（65535 附近递增 100 次耗尽）
  try {
    await ElMessageBox.alert(
      `端口 ${startPort} 已被占用${r.processInfo ? '：' + r.processInfo : ''}，且未找到空闲端口。`,
      '端口被占用', { type: 'error', confirmButtonText: '知道了' })
  } catch { /* 用户点知道了 */ }
  return { proceed: false, port: null }
}

async function onCreate() {
  if (!createForm.versionId || !createForm.serverId) {
    ElMessage.warning('请选择部署包版本和目标服务器')
    return
  }
  if (!createForm.serviceId) {
    ElMessage.warning('请选择要部署的服务')
    return
  }
  creating.value = true
  try {
    // 升级场景：查 (project, server) 在线服务端口，端口预检用于识别"占用者=自身服务"
    const ownPort = await fetchOnlinePort(createForm.projectId, createForm.serverId)
    // 确认部署前端口预检：仅 PROCESS 模式或用户显式指定端口时触发
    // （NGINX 空端口走后端默认 80；DOCKER 不使用表单端口，均无需预检）
    let finalPort = createForm.port
    if (createForm.mode === 'PROCESS' || createForm.port) {
      const chk = await runPortCheck(createForm.serverId, createForm.port, (p) => { createForm.port = p }, ownPort)
      if (!chk.proceed) return
      finalPort = chk.port ?? createForm.port
    }
    const payload = {
      versionId: createForm.versionId,
      serverId: createForm.serverId,
      mode: createForm.mode,
      startCmd: createForm.mode === 'PROCESS' ? (createForm.startCmd || undefined) : undefined,
      port: finalPort || undefined,
      healthPath: createForm.healthPath || undefined,
      // null = 不使用数据库；-1 = 自动创建项目独立库；正值 = 已登记库（后端校验）
      databaseId: createForm.mode === 'NGINX' ? null : createForm.databaseId,
    }
    await createDeployment(payload)
    ElMessage.success('部署任务已创建')
    createDialog.value = false
    router.replace({ name: 'deployments' })
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    creating.value = false
  }
}

// AI 生成部署方案：调用后端 ReAct（解析包 + 探测目标机），耗时较长
async function onAiGenerate() {
  if (!createForm.versionId || !createForm.serverId) {
    ElMessage.warning('请先选择部署包版本和目标服务器')
    return
  }
  aiGenerating.value = true
  try {
    const plan = await generateAiPlan({ versionId: createForm.versionId, serverId: createForm.serverId, mode: createForm.mode, port: createForm.port || undefined })
    aiPlan.value = plan
    // 用方案原值初始化可编辑字段（留空表示不改）
    aiEdit.mode = plan.mode
    aiEdit.startCmd = ''
    aiEdit.port = null
    aiEdit.healthPath = ''
    // 数据库沿用主表单已选值，用户可在 AI 对话框内再调整
    aiEdit.databaseId = createForm.mode === 'NGINX' || plan.mode === 'NGINX' ? null : createForm.databaseId
    createDialog.value = false
    aiDialog.value = true
  } catch (err) {
    // AI 生成耗时长（~20s+），失败原因关键（如包内缺 Dockerfile），用弹窗避免 toast 一闪而过被忽略
    ElMessageBox.alert(err?.message || 'AI 生成失败，请重试', 'AI 生成部署方案失败', { type: 'error', confirmButtonText: '知道了' })
  } finally {
    aiGenerating.value = false
  }
}

// 确认并执行：先端口预检，再提交微调后的 mode/startCmd/port/healthPath
async function onAiConfirm() {
  if (!aiPlan.value) return
  const effPort = aiEdit.port || aiPlan.value.port
  let finalPort = effPort
  // 升级场景：查 (project, server) 在线服务端口
  const ownPort = await fetchOnlinePort(aiPlan.value.projectId, aiPlan.value.serverId)
  // 端口预检门控：仅 PROCESS 模式或存在有效端口时触发
  // （NGINX 空端口走后端默认 80、DOCKER 不使用表单端口，跳过避免 8080 漂移/多余探测）
  if (aiEdit.mode === 'PROCESS' || effPort) {
    try {
    // 起点端口：用户微调端口 > 方案端口 > 8080（AI 方案已含项目/识别建议）
    const startPort = effPort || aiPlan.value.port || 8080
    let r
    try {
      r = await checkPort({ serverId: aiPlan.value.serverId, port: startPort })
    } catch (err) {
      toastError(err)
      return
    }
    if (!r.probed) {
      try {
        await ElMessageBox.confirm('目标机端口探测失败，将直接执行部署（执行阶段仍有健康检查兜底）。继续？', '端口探测失败', { type: 'warning' })
      } catch {
        return
      }
    } else if (r.occupied) {
      // 升级场景：占用端口=本项目在线服务端口 → 复用原端口
      if (ownPort && startPort === ownPort) {
        try {
          await ElMessageBox.confirm(
            `端口 ${startPort} 已被本项目当前在线服务占用（升级部署将替换原进程并复用该端口）。确认以此端口部署？`,
            '升级部署 · 复用端口', { type: 'info' })
        } catch {
          return
        }
        aiEdit.port = startPort
        finalPort = startPort
      } else if (r.recommendedPort) {
        try {
          await ElMessageBox.confirm(
            `端口 ${startPort} 已被占用${r.processInfo ? '：' + r.processInfo : ''}。<br>是否换到推荐空闲端口 ${r.recommendedPort}？`,
            '端口被占用',
            { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '换到推荐端口', cancelButtonText: '放弃' })
          aiEdit.port = r.recommendedPort
          finalPort = r.recommendedPort
        } catch {
          return
        }
      } else {
        try {
          await ElMessageBox.alert(`端口 ${startPort} 已被占用${r.processInfo ? '：' + r.processInfo : ''}，且未找到空闲端口。`, '端口被占用', { type: 'error', confirmButtonText: '知道了' })
        } catch { /* 知道了 */ }
        return
      }
    } else if (!effPort) {
      try {
        await ElMessageBox.confirm(`已自动分配端口 ${startPort}，确认以此部署？`, '自动分配端口', { type: 'info' })
        aiEdit.port = startPort
        finalPort = startPort
      } catch {
        return
      }
    }
    } catch {
      return
    }
  }
  try {
    await ElMessageBox.confirm(
      `将按 AI 方案执行部署（${aiEdit.mode} 模式${finalPort ? '，端口 ' + finalPort : ''}），并由平台安全管线接管上传/启动/健康检查，失败自动回滚。确认执行？`,
      '确认执行', { type: 'warning' })
  } catch {
    return
  }
  aiConfirming.value = true
  try {
    const payload = {
      mode: aiEdit.mode,
      startCmd: aiEdit.mode === 'PROCESS' ? (aiEdit.startCmd || undefined) : undefined,
      port: aiEdit.port || finalPort || undefined,
      healthPath: aiEdit.healthPath || undefined,
      databaseId: aiEdit.mode === 'NGINX' ? null : aiEdit.databaseId,
    }
    const updated = await confirmAiPlan(aiPlan.value.id, payload)
    ElMessage.success(`已确认，部署任务 #${updated.deploymentId} 已创建`)
    aiDialog.value = false
    aiPlan.value = null
    await load()
    if (updated.deploymentId) openDetail(updated.deploymentId)
  } catch (err) {
    toastError(err)
  } finally {
    aiConfirming.value = false
  }
}

// 放弃方案
async function onAiAbandon() {
  if (!aiPlan.value) return
  try {
    await ElMessageBox.confirm('确定放弃该 AI 部署方案？', '放弃确认', { type: 'warning' })
  } catch {
    return
  }
  aiAbandoning.value = true
  try {
    await abandonAiPlan(aiPlan.value.id)
    ElMessage.success('已放弃方案')
    aiDialog.value = false
    aiPlan.value = null
  } catch (err) {
    toastError(err)
  } finally {
    aiAbandoning.value = false
  }
}

async function onCancel(row) {
  try {
    await ElMessageBox.confirm(`确定取消部署 #${row.id}？`, '取消确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await cancelDeployment(row.id)
    ElMessage.success('已请求取消')
    await load()
  } catch (err) {
    toastError(err)
  }
}

const redeploying = ref(false)
const rollbacking = ref(false)

async function onRollback(row) {
  try {
    await ElMessageBox.confirm(
      `将部署 #${row.id} 回退到它替代的旧版本？平台会以旧版本参数新建一次部署并完成实例切换。`,
      '版本回退', { type: 'warning' })
  } catch {
    return
  }
  rollbacking.value = true
  try {
    const vo = await rollbackDeployment(row.id)
    ElMessage.success(`回退任务 #${vo.id} 已创建`)
    if (detail.value && detail.value.id === row.id) {
      await openDetail(row.id)
    }
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    rollbacking.value = false
  }
}

async function onRedeploy(row) {
  try {
    await ElMessageBox.confirm(
      `按部署 #${row.id} 的原参数（版本/服务器/端口/命令）新建一次部署？`,
      '重新部署', { type: 'warning' })
  } catch {
    return
  }
  // 端口预检（沿用原端口）：SSH 失败放行 / 空闲直接 redeploy / 被占可换端口（换端口走 create）
  if (row.port) {
    let r
    try {
      r = await checkPort({ serverId: row.serverId, port: row.port })
    } catch (err) {
      toastError(err)
      return
    }
    if (r.probed && r.occupied) {
      if (r.recommendedPort) {
        try {
          await ElMessageBox.confirm(
            `端口 ${row.port} 已被占用${r.processInfo ? '：' + r.processInfo : ''}。<br>是否换到推荐空闲端口 ${r.recommendedPort} 重新部署？`,
            '端口被占用',
            { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '换到推荐端口', cancelButtonText: '放弃' })
          redeploying.value = true
          try {
            await createDeployment({
              versionId: row.versionId,
              serverId: row.serverId,
              mode: row.mode,
              startCmd: row.mode === 'PROCESS' ? (row.startCmd || undefined) : undefined,
              port: r.recommendedPort,
              healthPath: row.healthPath || undefined,
              databaseId: row.databaseId,
            })
            ElMessage.success(`已换端口 ${r.recommendedPort} 创建新的部署任务`)
            detailVisible.value = false
            await load()
          } catch (err2) {
            toastError(err2)
          } finally {
            redeploying.value = false
          }
          return
        } catch {
          return // 放弃
        }
      }
      try {
        await ElMessageBox.alert(`端口 ${row.port} 已被占用${r.processInfo ? '：' + r.processInfo : ''}，且未找到空闲端口。`, '端口被占用', { type: 'error', confirmButtonText: '知道了' })
      } catch { /* 知道了 */ }
      return
    }
    if (!r.probed) {
      try {
        await ElMessageBox.confirm('目标机端口探测失败，将直接重新部署（执行阶段仍有健康检查兜底）。继续？', '端口探测失败', { type: 'warning' })
      } catch {
        return
      }
    }
  }
  redeploying.value = true
  try {
    await redeployDeployment(row.id)
    ElMessage.success('已创建新的部署任务')
    detailVisible.value = false
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    redeploying.value = false
  }
}

async function openDetail(id) {
  detailVisible.value = true
  detail.value = null
  steps.value = []
  stopPolling()
  try {
    await refreshDetail(id)
  } catch (err) {
    toastError(err)
  }
  if (!isDeployTerminal(detail.value?.status)) {
    pollTimer = setInterval(() => {
      refreshDetail(id).catch(() => {})
    }, 3000)
  }
}

async function refreshDetail(id) {
  const [d, s] = await Promise.all([getDeployment(id), getSteps(id)])
  detail.value = d
  steps.value = s
  // 同步列表行状态，避免关闭详情前列表仍是旧状态
  const row = rows.value.find((r) => r.id === id)
  if (row) Object.assign(row, d)
  if (isDeployTerminal(d.status)) stopPolling()
}

function onDetailClose() {
  stopPolling()
  // 关闭详情后刷新列表，保证状态与后端一致
  load()
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function goDocuments(row) {
  router.push({ name: 'documents', query: { deployment: row.id } })
}

onMounted(async () => {
  await load()
  const qVersion = Number(route.query.version) || null
  if (qVersion) prefillVersion.value = qVersion
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 2px;
  width: 100%;
}
.step-title {
  font-weight: 600;
  margin-bottom: 12px;
}
.step-log {
  margin-top: 8px;
  padding: 8px;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: Consolas, monospace;
  font-size: 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}
.step-detail {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
.ai-reasoning {
  padding: 10px 12px;
  background: #f5f7fa;
  border-left: 3px solid #409eff;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
  white-space: pre-wrap;
}
.cmd-block {
  margin-bottom: 10px;
}
.cmd-label {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 4px;
}
.traj-step {
  margin-bottom: 10px;
}
.traj-step .step-log {
  margin-top: 4px;
}
.ai-mode-dimmed-active {
  opacity: 0.45;
  filter: grayscale(0.6);
  transition: opacity 0.2s;
}
</style>
