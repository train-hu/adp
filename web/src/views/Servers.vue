<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>服务器列表（{{ rows.length }}）</span>
          <el-button
            v-if="canManage"
            type="primary"
            :icon="Plus"
            @click="openEdit(null)"
          >
            添加服务器
          </el-button>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="IP:端口" min-width="140">
          <template #default="{ row }">{{ row.ip }}:{{ row.sshPort }}</template>
        </el-table-column>
        <el-table-column prop="sshUser" label="SSH 用户" width="110" />
        <el-table-column prop="authType" label="认证方式" width="100" />
        <el-table-column prop="envTag" label="环境" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="serverStatus(row.status).type" size="small">
              {{ serverStatus(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近探测" width="170">
          <template #default="{ row }">{{ fmtTime(row.lastCheckAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :loading="row._testing" @click="onTest(row)">
              连通性测试
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑服务器' : '添加服务器'" width="520">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 test-server-01" />
        </el-form-item>
        <el-form-item label="IP" required>
          <el-input v-model="form.ip" placeholder="如 192.168.1.10" />
        </el-form-item>
        <el-form-item label="SSH 端口">
          <el-input-number v-model="form.sshPort" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="SSH 用户" required>
          <el-input v-model="form.sshUser" placeholder="如 root" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="form.authType" style="width: 100%">
            <el-option label="密码" value="PASSWORD" />
            <el-option label="私钥" value="KEY" />
          </el-select>
        </el-form-item>
        <el-form-item label="密码/私钥" :required="!form.id">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="form.id ? '留空表示不修改' : 'SSH 密码或私钥 PEM 内容'"
          />
        </el-form-item>
        <el-form-item label="环境标签">
          <el-input v-model="form.envTag" placeholder="dev / test / prod" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分配可见用户（仅 ADMIN）-->
    <el-dialog v-model="grantDialog" :title="`分配服务器「${grantTarget?.name}」的可见用户`" width="560">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px">
        被分配的用户将可见并可管理该服务器；任运维创建的对所有运维可见
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
  listServers, createServer, updateServer, deleteServer, testServer,
  listServerGrants, listServerGrantCandidates, addServerGrant, removeServerGrant,
} from '../api/services'
import { serverStatus, fmtTime } from '../utils/status'

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

function emptyForm() {
  return { id: null, name: '', ip: '', sshPort: 22, sshUser: '', authType: 'PASSWORD', password: '', envTag: 'dev' }
}

async function load() {
  loading.value = true
  try {
    rows.value = (await listServers()).map((r) => ({ ...r, _testing: false }))
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, row ? {
    id: row.id, name: row.name, ip: row.ip, sshPort: row.sshPort,
    sshUser: row.sshUser, authType: row.authType, envTag: row.envTag, password: '',
  } : emptyForm())
  dialogVisible.value = true
}

async function onSave() {
  if (!form.name || !form.ip || !form.sshUser) {
    ElMessage.warning('名称 / IP / SSH 用户为必填项')
    return
  }
  if (!form.id && !form.password) {
    ElMessage.warning('新增服务器必须填写密码或私钥')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name, ip: form.ip, sshPort: form.sshPort,
      sshUser: form.sshUser, authType: form.authType, envTag: form.envTag,
    }
    if (form.password) payload.password = form.password
    if (form.id) {
      await updateServer(form.id, payload)
    } else {
      await createServer(payload)
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
    await ElMessageBox.confirm(`确定删除服务器「${row.name}」？该操作不可恢复。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteServer(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (err) {
    toastError(err)
  }
}

async function onTest(row) {
  row._testing = true
  try {
    const r = await testServer(row.id)
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
    grants.value = await listServerGrants(row.id)
    grantCandidates.value = await listServerGrantCandidates()
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
    await addServerGrant(grantTarget.value.id, grantForm.userId)
    ElMessage.success('已分配')
    grantForm.userId = null
    grants.value = await listServerGrants(grantTarget.value.id)
  } catch (err) {
    toastError(err)
  } finally {
    grantSaving.value = false
  }
}

async function onRemoveGrant(row) {
  try {
    await ElMessageBox.confirm(`确定移除 ${row.username} 对该服务器的可见权限？`, '移除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await removeServerGrant(grantTarget.value.id, row.userId)
    ElMessage.success('已移除')
    grants.value = await listServerGrants(grantTarget.value.id)
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
