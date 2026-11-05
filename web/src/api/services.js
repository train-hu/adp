import http, { getToken } from './http'

// ---- 仪表盘 ----
export const getDashboard = () => http.get('/dashboard')

// ---- 服务器管理 ----
export const listServers = () => http.get('/servers')
export const getServer = (id) => http.get(`/servers/${id}`)
export const createServer = (payload) => http.post('/servers', payload)
export const updateServer = (id, payload) => http.put(`/servers/${id}`, payload)
export const deleteServer = (id) => http.delete(`/servers/${id}`)
export const testServer = (id) => http.post(`/servers/${id}/test`)
// 资源可见性分配（仅 ADMIN）：服务器/数据库按用户分配
export const listServerGrants = (id) => http.get(`/servers/${id}/grants`)
export const listServerGrantCandidates = () => http.get('/servers/grant-candidates')
export const addServerGrant = (id, userId) => http.post(`/servers/${id}/grants`, { userId })
export const removeServerGrant = (id, userId) => http.delete(`/servers/${id}/grants/${userId}`)

// ---- 数据库管理 ----
export const listDatabases = () => http.get('/databases')
export const getDatabase = (id) => http.get(`/databases/${id}`)
export const createDatabase = (payload) => http.post('/databases', payload)
export const updateDatabase = (id, payload) => http.put(`/databases/${id}`, payload)
export const deleteDatabase = (id) => http.delete(`/databases/${id}`)
// 连通性测试（TCP 端口探测，后端 5s 超时）
export const testDatabase = (id) => http.post(`/databases/${id}/test`, null, { timeout: 15000 })
export const listDatabaseGrants = (id) => http.get(`/databases/${id}/grants`)
export const listDatabaseGrantCandidates = () => http.get('/databases/grant-candidates')
export const addDatabaseGrant = (id, userId) => http.post(`/databases/${id}/grants`, { userId })
export const removeDatabaseGrant = (id, userId) => http.delete(`/databases/${id}/grants/${userId}`)

// ---- 项目 / 部署包 ----
export const listProjects = () => http.get('/projects')
export const getProject = (id) => http.get(`/projects/${id}`)
export const createProject = (payload) => http.post('/projects', payload)
export const updateProject = (id, payload) => http.put(`/projects/${id}`, payload)
export const deleteProject = (id) => http.delete(`/projects/${id}`)
export const listVersions = (projectId, serviceId) =>
  http.get(`/projects/${projectId}/packages`, { params: serviceId ? { serviceId } : {} })
export const deleteVersion = (versionId) => http.delete(`/projects/packages/${versionId}`)
// 包下载走 http.js 的 downloadBlob（Blob → 浏览器保存）

// ---- 子服务（1 项目 N 服务：包/配置/部署按服务作用域；默认服务 pathKey=null 走旧口径）----
export const listSubServices = (projectId) => http.get(`/projects/${projectId}/sub-services`)
export const createSubService = (projectId, payload) => http.post(`/projects/${projectId}/sub-services`, payload)
export const deleteSubService = (projectId, serviceId) =>
  http.delete(`/projects/${projectId}/sub-services/${serviceId}`)

// ---- 分片上传 + 断点续传（大文件按 10MB 切片，逐片幂等上传，可跳过已传片）----
// 服务端合并后实算 sha256 为准；sha256 参数可选，仅用于与服务端实算值比对
export const initChunkUpload = (projectId, payload) => http.post(`/projects/${projectId}/upload-init`, payload)
export const uploadChunk = (projectId, sessionId, index, blob, fileName) => {
  const formData = new FormData()
  formData.append('file', blob, fileName)
  return http.post(
    `/projects/${projectId}/upload-chunk?sessionId=${encodeURIComponent(sessionId)}&index=${index}`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 0 },
  )
}
export const getUploadProgress = (projectId, sessionId) =>
  http.get(`/projects/${projectId}/upload-progress`, { params: { sessionId } })
export const completeChunkUpload = (projectId, payload) =>
  http.post(`/projects/${projectId}/upload-complete`, payload)

// ---- 项目配置文件（nginx / compose / .env 等，随部署下发）----
export const listConfigFiles = (projectId, serviceId) =>
  http.get(`/projects/${projectId}/config-files`, { params: serviceId ? { serviceId } : {} })
export const saveConfigFile = (projectId, payload, serviceId) =>
  http.put(`/projects/${projectId}/config-files`, payload,
    { params: serviceId ? { serviceId } : {} })
export const deleteConfigFile = (projectId, fileKey, serviceId) =>
  http.delete(`/projects/${projectId}/config-files/${fileKey}`,
    { params: serviceId ? { serviceId } : {} })

// ---- 部署包内文件树 / 单文件读取（目录树配置：浏览包内文件并导入为环境覆盖）----
// 返回相对路径清单（正斜杠，目录带 '/' 尾缀）
export const getPackageTree = (versionId) => http.get(`/projects/packages/${versionId}/tree`)
// 读取包内单文件内容（relPath 与 tree 一致），返回 { relPath, content }
export const getPackageFile = (versionId, relPath) =>
  http.get(`/projects/packages/${versionId}/file`, { params: { relPath } })

// ---- 用户管理（仅 ADMIN）----
export const listUsers = () => http.get('/users')
export const getUser = (id) => http.get(`/users/${id}`)
export const createUser = (payload) => http.post('/users', payload)
export const updateUser = (id, payload) => http.put(`/users/${id}`, payload)
export const deleteUser = (id) => http.delete(`/users/${id}`)

// ---- 项目成员管理（ADMIN 或项目成员）----
export const listProjectMembers = (projectId) => http.get(`/projects/${projectId}/members`)
export const addProjectMember = (projectId, payload) => http.post(`/projects/${projectId}/members`, payload)
export const removeProjectMember = (projectId, userId) =>
  http.delete(`/projects/${projectId}/members/${userId}`)
// 添加成员候选用户（可管理项目者可见）：返回 id/username/role/enabled，不含 projectIds
export const listMemberCandidates = (projectId) => http.get(`/projects/${projectId}/member-candidates`)

// ---- 部署任务 ----
export const listDeployments = () => http.get('/deployments')
export const getDeployment = (id) => http.get(`/deployments/${id}`)
export const getSteps = (id) => http.get(`/deployments/${id}/steps`)
export const createDeployment = (payload) => http.post('/deployments', payload)
export const cancelDeployment = (id) => http.post(`/deployments/${id}/cancel`)
export const redeployDeployment = (id) => http.post(`/deployments/${id}/redeploy`)
// 一键版本回退：把某次已完成部署回退到它替代的旧版本
export const rollbackDeployment = (id) => http.post(`/deployments/${id}/rollback`)
// 端口预检：SSH 目标机 ss -lntp（后端 10s 超时），SSH 失败返回 probed=false 由调用方放行
export const checkPort = (payload) => http.post('/deployments/check-port', payload, { timeout: 30000 })

// ---- 文档 ----
export const getDocument = (id, type) => http.get(`/deployments/${id}/documents`, { params: { type } })
export const downloadDocument = (id, type) =>
  http.get(`/deployments/${id}/documents/download`, { params: { type }, responseType: 'blob' })
// 重新生成同时产出 deploy + maintain 两份文档（后端无 type 参数）
export const regenerateDocument = (id) => http.post(`/deployments/${id}/documents/regenerate`)

// ---- 服务实例 ----
export const listServiceInstances = (params) => http.get('/service-instances', { params })
export const getServiceInstance = (id) => http.get(`/service-instances/${id}`)
export const startInstance = (id) => http.post(`/service-instances/${id}/start`)
export const stopInstance = (id) => http.post(`/service-instances/${id}/stop`)
export const restartInstance = (id) => http.post(`/service-instances/${id}/restart`)
export const deleteServiceInstance = (id) => http.delete(`/service-instances/${id}`)

// ---- 站内通知（健康探针等事件）----
export const listNotifications = () => http.get('/notifications')
export const getUnreadCount = () => http.get('/notifications/unread-count')
export const readAllNotifications = () => http.post('/notifications/read-all')
export const readNotification = (id) => http.post(`/notifications/${id}/read`)

// ---- LLM 模型配置（M2 AI 能力）----
export const listLlmModels = () => http.get('/llm-models')
export const getLlmModel = (id) => http.get(`/llm-models/${id}`)
export const createLlmModel = (payload) => http.post('/llm-models', payload)
export const updateLlmModel = (id, payload) => http.put(`/llm-models/${id}`, payload)
export const deleteLlmModel = (id) => http.delete(`/llm-models/${id}`)
export const testLlmModel = (id) => http.post(`/llm-models/${id}/test`)
export const chatTestLlmModel = (id, prompt) => http.post(`/llm-models/${id}/chat-test`, { prompt })

// ---- AI 自主部署（M2 AI 能力：ReAct 生成方案 → 人工确认执行）----
// 生成方案耗时较长（多轮 LLM 调用），放宽该请求超时
export const generateAiPlan = (payload) =>
  http.post('/ai-deployments/generate', payload, { timeout: 180000 })
export const listAiPlans = (projectId) => http.get('/ai-deployments', { params: { projectId } })
export const getAiPlan = (id) => http.get(`/ai-deployments/${id}`)
export const confirmAiPlan = (id, payload) => http.post(`/ai-deployments/${id}/confirm`, payload || {}, { timeout: 60000 })
export const abandonAiPlan = (id) => http.post(`/ai-deployments/${id}/abandon`)

/**
 * 实时日志 SSE：GET /api/service-instances/{id}/logs
 * 后端事件名固定 "log"，数据为 journalctl 单行；30 分钟超时，连接关闭即结束。
 * 必须携带 Authorization 头 → 不能用原生 EventSource，用 fetch + ReadableStream 手动解析帧。
 * @param {Function} onLine 每收到一行日志的回调
 * @param {Function} [onClose] 流自然结束（后端超时/断开）或出错时回调；主动 abort() 不触发
 * @returns {AbortController} 调 abort() 可停止日志流
 */
export function streamLogs(instanceId, onLine, onClose) {
  const controller = new AbortController()
  const token = getToken()
  const decoder = new TextDecoder()
  let buffer = ''

  fetch(`/api/service-instances/${instanceId}/logs`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`日志连接失败：HTTP ${response.status}`)
      }
      const reader = response.body.getReader()
      const pump = async () => {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          // SSE 帧以空行（\n\n）分隔
          let idx
          while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const frame = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            for (const rawLine of frame.split('\n')) {
              const line = rawLine.replace(/\r$/, '')
              if (line.startsWith('data:')) {
                onLine(line.slice(5).trimStart())
              }
              // event:/id:/retry: 行忽略（数据统一在 data:）
            }
          }
        }
      }
      await pump()
      onClose?.()
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onLine(`[日志流断开] ${err.message}`)
        onClose?.()
      }
    })

  return controller
}


// ---- LLM 模型配置：设为默认 ----
export const setDefaultLlmModel = (id) => http.put('/llm-models/' + id + '/default')

// ---- AI 助手（对话 + 会话历史）----
// AI 回复可能较长（ReAct 多轮），放宽到 180s 与 generateAiPlan 一致
export const chatAssistant = (payload) =>
  http.post('/ai-assistant/chat', payload, { timeout: 180000 })
export const getAiHistory = (conversationId) =>
  http.get('/ai-assistant/history', { params: { conversationId } })
export const getAiConversations = () => http.get('/ai-assistant/conversations')

// ---- Webhook 告警配置（仅 ADMIN）----
export const listWebhooks = () => http.get('/webhooks')
export const createWebhook = (payload) => http.post('/webhooks', payload)
export const updateWebhook = (id, payload) => http.put(`/webhooks/${id}`, payload)
export const deleteWebhook = (id) => http.delete(`/webhooks/${id}`)
// 启用/禁用：PUT 部分更新（仅 enabled 字段）
export const setWebhookEnabled = (id, enabled) => http.put(`/webhooks/${id}`, { enabled })

// ---- 监控指标（任何已登录角色）----
export const getMonitorSummary = () => http.get('/monitor/summary')
