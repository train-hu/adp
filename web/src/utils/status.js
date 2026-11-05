/** 部署状态 → 中文 + 类型 */
const DEPLOY_STATUS = {
  PENDING: { label: '等待中', type: 'info' },
  UPLOADING: { label: '上传中', type: 'primary' },
  INSTALLING: { label: '安装中', type: 'primary' },
  STARTING: { label: '启动中', type: 'primary' },
  VERIFYING: { label: '验证中', type: 'warning' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  ROLLING_BACK: { label: '回滚中', type: 'warning' },
  ROLLED_BACK: { label: '已回滚', type: 'danger' },
  NEEDS_ATTENTION: { label: '需人工处理', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
}

const STEP_STATUS = {
  PENDING: { label: '待执行', type: 'info' },
  RUNNING: { label: '执行中', type: 'primary' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  SKIPPED: { label: '已跳过', type: 'warning' },
}

const SERVER_STATUS = {
  ONLINE: { label: '在线', type: 'success' },
  OFFLINE: { label: '离线', type: 'danger' },
  UNKNOWN: { label: '未知', type: 'info' },
}

const INSTANCE_STATUS = {
  RUNNING: { label: '运行中', type: 'success' },
  STOPPED: { label: '已停止', type: 'info' },
}

function mapStatus(table, status) {
  return table[status] || { label: status || '-', type: 'info' }
}

export function deployStatus(status) {
  return mapStatus(DEPLOY_STATUS, status)
}
export function stepStatus(status) {
  return mapStatus(STEP_STATUS, status)
}
export function serverStatus(status) {
  return mapStatus(SERVER_STATUS, status)
}
export function instanceStatus(status) {
  return mapStatus(INSTANCE_STATUS, status)
}

// 部署终态集合（前端轮询停止判定）
export const DEPLOY_TERMINAL = ['SUCCESS', 'FAILED', 'ROLLED_BACK', 'NEEDS_ATTENTION', 'CANCELLED']

export function isDeployTerminal(status) {
  return DEPLOY_TERMINAL.includes(status)
}

export function fmtTime(iso) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}
