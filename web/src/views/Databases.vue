<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>数据库列表（{{ rows.length }}）</span>
          <el-button
            v-if="canManage"
            type="primary"
            :icon="Plus"
            @click="openEdit(null)"
          >
            登记数据库
          </el-button>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="dbType" label="类型" width="110" />
        <el-table-column label="地址" min-width="140">
          <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column prop="databaseName" label="库名" min-width="120" show-overflow-tooltip />
        <el-table-column prop="username" label="账号" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="dbStatus(row.status).type" size="small">
              {{ dbStatus(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近探测" width="170">
          <template #default="{ row }">{{ fmtTime(row.lastCheckAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :loading="row._testing" @click="onTest(row)">
              连接测试
            </el-button>
            <el-button v-if="canManage" link type="primary" size="small" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-if="canManage" link type="danger" size="small" @click="onDelete(row)">
              删除
            </el-button>
            <el-button v-if="canAdmin" link type="primary" size="small" @click="openGrantDialog(row)">
              分配
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据库' : '登记数据库'" width="520">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 prod-mysql-01" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.dbType" style="width: 100%" @change="onDbTypeChange">
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="PostgreSQL" value="POSTGRESQL" />
            <el-option label="Redis" value="REDIS" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机" required>
          <el-input v-model="form.host" placeholder="如 192.168.1.20" />
        </el-form-item>
        <el-form-item label="端口" required>
          <el-input-number v-model="form.port" :min="1" :max="65535" style="width: 180px" />
        </el-form-item>
        <el-form-item v-if="form.dbType !== 'REDIS'" label="库名">
          <el-input v-model="form.databaseName" :placeholder="form.dbType === 'POSTGRESQL' ? '如 mydb（默认 postgres）' : '如 mydb'" />
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="如 root" />
        </el-form-item>
        <el-form-item label="密码" :required="!form.id">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="form.id ? '留空表示不修改' : '数据库密码（加密存储）'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分配可见用户（仅 ADMIN）-->
    <el-dialog v-model="grantDialog" :title="`分配数据库「${grantTarget?.name}」的可见用户`" width="560">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px">
        被分配的用户将可见并可管理该数据库；任运维创建的对所有运维可见
      </el-alert>
      <el-table :data="grants" v-loading="grantsLoading" border size="small" style="margin-bottom: 12px">
        <el-table-column prop="username" label="用户" min-width="120" />
        <el-table-column prop="userRole" label="角色" width="100" />
        <el-table-column label="分配时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.grantedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="onRemoveGrant(row)">
              移除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>暂无分配</template>
      </el-table>
      <el-form inline @submit.prevent>
        <el-form-item label="添加用户">
          <el-select
            v-model="grantForm.userId"
            placeholder="选择用户"
            filterable
            style="width: 200px"
          >
            <el-option
              v-for="u in grantCandidates"
              :key="u.id"
              :label="`${u.username}（${u.role}）`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="grantSaving" @click="onAddGrant">添加</el-button>
        </el-form-item>
      </el-form>
    </el-dialog>
  </Layout>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import Layout from '../components/Layout.vue'
import { hasRole, toastError } from '../api/http'
import {
  listDatabases, createDatabase, updateDatabase, deleteDatabase, testDatabase,
  listDatabaseGrants, listDatabaseGrantCandidates, addDatabaseGrant, removeDatabaseGrant,
} from '../api/services'
import { fmtTime } from '../utils/status'

const canAdmin = computed(() => hasRole('ADMIN'))
// ADMIN 或 OPS 均可管理可见资源（OPS 见自己可见的，ADMIN 见全部）
const canManage = computed(() => hasRole('OPS'))
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const dialogVisible = ref(false)
const form = reactive(emptyForm())

// 分配可见用户（仅 ADMIN）
const grantDialog = ref(false)
const grantSaving = ref(false)
const grantsLoading = ref(false)
const grantTarget = ref(null)
const grants = ref([])
const grantCandidates = ref([])
const grantForm = reactive({ userId: null })

// 按类型填充默认端口/库名（仅新增或类型切换时生效，不覆盖已有值）
const DB_DEFAULTS = {
  MYSQL: { port: 3306, databaseName: '' },
  POSTGRESQL: { port: 5432, databaseName: 'postgres' },
  REDIS: { port: 6379, databaseName: '' },
}

function dbStatus(status) {
  return {
    ONLINE: { type: 'success', label: '在线' },
    OFFLINE: { type: 'danger', label: '离线' },
  }[status] || { type: 'info', label: '未探测' }
}

function emptyForm() {
  return { id: null, name: '', dbType: 'MYSQL', host: '', port: 3306, databaseName: '', username: '', password: '' }
}

function onDbTypeChange() {
  if (form.id) return // 编辑时不自动改动已有值
  const d = DB_DEFAULTS[form.dbType]
  if (!form.port || [3306, 5432, 6379].includes(form.port)) {
    form.port = d.port
  }
  if (form.dbType !== 'REDIS' && !form.databaseName) {
    form.databaseName = d.databaseName
  }
  if (form.dbType === 'REDIS') form.databaseName = ''
}

async function load() {
  loading.value = true
  try {
    rows.value = (await listDatabases()).map((r) => ({ ...r, _testing: false }))
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, row ? {
    id: row.id, name: row.name, dbType: row.dbType, host: row.host, port: row.port,
    databaseName: row.databaseName, username: row.username, password: '',
  } : emptyForm())
  dialogVisible.value = true
}

async function onSave() {
  if (!form.name || !form.dbType || !form.host || !form.port) {
    ElMessage.warning('名称 / 类型 / 主机 / 端口为必填项')
    return
  }
  if (!form.id && !form.password) {
    ElMessage.warning('请确认密码，数据库密码留空将按无密码方式测试连接')
  }
  saving.value = true
  try {
    const payload = {
      name: form.name, dbType: form.dbType, host: form.host, port: form.port,
      databaseName: form.databaseName || undefined, username: form.username || undefined,
    }
    if (form.password) payload.password = form.password
    if (form.id) {
      await updateDatabase(form.id, payload)
    } else {
      await createDatabase(payload)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除数据库「${row.name}」？已引用该库的部署任务不受影响（连接信息已快照），但后续新部署将无法再选此库。`,
      '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDatabase(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (err) {
    toastError(err)
  }
}

async function onTest(row) {
  row._testing = true
  try {
    const r = await testDatabase(row.id)
    if (r.reachable) {
      ElMessage.success(`${row.name} 可达（延迟 ${r.latencyMs} ms，${r.status}）`)
    } else {
      ElMessage.error(`${row.name} 不可达：${r.status}`)
    }
    await load()
  } catch (err) {
    toastError(err)
  } finally {
    row._testing = false
  }
}

async function openGrantDialog(row) {
  grantTarget.value = row
  grantForm.userId = null
  grants.value = []
  grantDialog.value = true
  grantsLoading.value = true
  try {
    grants.value = await listDatabaseGrants(row.id)
    grantCandidates.value = await listDatabaseGrantCandidates()
  } catch (err) {
    toastError(err)
  } finally {
    grantsLoading.value = false
  }
}

async function onAddGrant() {
  if (!grantForm.userId) {
    ElMessage.warning('请选择用户')
    return
  }
  grantSaving.value = true
  try {
    await addDatabaseGrant(grantTarget.value.id, grantForm.userId)
    ElMessage.success('已分配')
    grantForm.userId = null
    grants.value = await listDatabaseGrants(grantTarget.value.id)
  } catch (err) {
    toastError(err)
  } finally {
    grantSaving.value = false
  }
}

async function onRemoveGrant(row) {
  try {
    await ElMessageBox.confirm(`确定移除 ${row.username} 对该数据库的可见权限？`, '移除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await removeDatabaseGrant(grantTarget.value.id, row.userId)
    ElMessage.success('已移除')
    grants.value = await listDatabaseGrants(grantTarget.value.id)
  } catch (err) {
    toastError(err)
  }
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
