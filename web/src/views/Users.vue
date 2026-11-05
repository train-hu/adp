<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>用户列表（{{ rows.length }}）</span>
          <el-button
            v-if="canAdmin"
            type="primary"
            :icon="Plus"
            @click="openEdit(null)"
          >
            添加用户
          </el-button>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column label="全局角色" width="110">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" size="small">{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已分配项目" width="100">
          <template #default="{ row }">{{ (row.projectIds || []).length }} 个</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canAdmin" label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              :disabled="row.id === currentUserId"
              @click="onDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '添加用户'" width="480">
      <el-form :model="form" label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!form.id" placeholder="登录用户名" />
        </el-form-item>
        <el-form-item label="密码" :required="!form.id">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="form.id ? '留空表示不修改（至少 6 位）' : '初始密码（至少 6 位）'"
          />
        </el-form-item>
        <el-form-item label="全局角色" required>
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="管理员（全部权限）" value="ADMIN" />
            <el-option label="运维（可部署）" value="OPS" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.id" label="状态">
          <el-switch
            v-model="form.enabled"
            active-text="启用"
            inactive-text="禁用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </Layout>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import Layout from '../components/Layout.vue'
import { hasRole, toastError, getUsername } from '../api/http'
import { listUsers, createUser, updateUser, deleteUser } from '../api/services'
import { fmtTime } from '../utils/status'

const canAdmin = computed(() => hasRole('ADMIN'))
const currentUsername = getUsername()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const dialogVisible = ref(false)
const form = reactive(emptyForm())

// 当前登录用户 id（用于禁用「删除自己」按钮）
const currentUserId = computed(() => rows.value.find((r) => r.username === currentUsername)?.id ?? null)

function emptyForm() {
  return { id: null, username: '', password: '', role: 'OPS', enabled: true }
}

function roleTagType(role) {
  return { ADMIN: 'danger', OPS: 'warning' }[role] || 'info'
}

async function load() {
  loading.value = true
  try {
    rows.value = await listUsers()
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, row ? {
    id: row.id, username: row.username, role: row.role,
    enabled: row.enabled, password: '',
  } : emptyForm())
  dialogVisible.value = true
}

async function onSave() {
  if (!form.id && (!form.username || !form.password)) {
    ElMessage.warning('新增用户必须填写用户名和密码')
    return
  }
  if (form.password && form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      const payload = { role: form.role, enabled: form.enabled }
      if (form.password) payload.password = form.password
      await updateUser(form.id, payload)
    } else {
      await createUser({ username: form.username, password: form.password, role: form.role })
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
      `确定删除用户「${row.username}」？该操作不可恢复。若该用户关联项目成员、部署记录等将被拒绝。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    await load()
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
