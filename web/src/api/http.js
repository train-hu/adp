import axios from 'axios'
import { ElMessage } from 'element-plus'

// token 存 sessionStorage（关标签页即失效，符合 M1 最小前端定位）
export function getToken() {
  return sessionStorage.getItem('token') || ''
}

// 会话状态变化事件：setSession / clearSession 后 dispatch，
// 让读取 getToken() 的响应式组件（如 AI 助手浮窗）能即时刷新，避免登录后需刷新页面。
function notifyAuthChange() {
  window.dispatchEvent(new CustomEvent('auth-change'))
}

export function setToken(token) {
  if (token) sessionStorage.setItem('token', token)
  else sessionStorage.removeItem('token')
  notifyAuthChange()
}

export function getUsername() {
  return sessionStorage.getItem('username') || ''
}

export function setSession(token, username, role) {
  sessionStorage.setItem('token', token)
  sessionStorage.setItem('username', username || '')
  sessionStorage.setItem('role', role || '')
  notifyAuthChange()
}

export function getRole() {
  return sessionStorage.getItem('role') || ''
}

export function clearSession() {
  sessionStorage.removeItem('token')
  sessionStorage.removeItem('username')
  sessionStorage.removeItem('role')
  notifyAuthChange()
}

// 角色等级（与后端 RequireRoleAspect 对齐）：当前角色是否满足最低要求
const ROLE_LEVEL = { ADMIN: 3, OPS: 2 }
export function hasRole(minRole) {
  return (ROLE_LEVEL[getRole()] || 0) >= (ROLE_LEVEL[minRole] || 99)
}

const http = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 统一按 body.code 判成功：后端 500/5001-5004 错误也用 HTTP 200 返回
function unwrap(response) {
  const { status, config, data } = response
  // 二进制/文件流下载不走 Result 包装，直接返回 Blob
  if (config?.responseType === 'blob') return data
  if (data && typeof data === 'object' && 'code' in data) {
    if (data.code === 0) return data.data
    // 5xxx / 500：HTTP 200 + body code；4xx：真实 HTTP 状态 + body code
    throw new ApiError(status, data.code, data.message || '请求失败', data)
  }
  return data
}

export class ApiError extends Error {
  constructor(httpStatus, code, message, body) {
    super(message)
    this.httpStatus = httpStatus
    this.code = code
    this.body = body
  }
}

http.interceptors.response.use(
  (response) => unwrap(response),
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    // 401：清会话跳登录（登录页的 401 由调用方自行处理）
    if (status === 401 && !location.pathname.startsWith('/login')) {
      clearSession()
      location.href = '/login'
    }
    const message = (body && body.message) || error.message || '网络错误'
    return Promise.reject(new ApiError(status, body?.code, message, body))
  },
)

// 统一错误提示（避免调用方重复写 ElMessage）
export function toastError(err) {
  ElMessage.error(err?.message || '操作失败')
}

export function downloadBlob(url, filename) {
  return http.get(url, { responseType: 'blob' }).then((blob) => {
    const objURL = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = objURL
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(objURL)
  })
}

export default http
