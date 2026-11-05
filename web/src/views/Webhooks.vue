<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Webhook 告警（{{ rows.length }}）</span>
          <el-button type="primary" :icon="Plus" @click="openEdit(null)">新增 Webhook</el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="部署失败、服务健康异常等事件会推送到启用的 webhook（飞书 / 钉钉机器人）。"
        style="margin-bottom: 14px"
      />

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="row.type === 'FEISHU' ? 'primary' : 'warning'" size="small">
              {{ row.type === 'FEISHU' ? '飞书' : '钉钉' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="url" label="Webhook 地址" min-width="260">
          <template #default="{ row }">
            <span class="url-text">{{ row.url }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingId === row.id"
              inline-prompt
              active-text="启用"
              inactive-text="禁用"
              @change="(val) => onToggle(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="暂无 Webhook，点击右上角新增" :image-size="72" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑 Webhook' : '新增 Webhook'" width="520">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" placeholder="例如：生产告警群" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="飞书机器人" value="FEISHU" />
            <el-option label="钉钉机器人" value="DINGTALK" />
          </el-select>
        </el-form-item>
        <el-form-item label="Webhook 地址" required>
          <el-input
            v-model="form.url"
            type="textarea"
            :rows="2"
            placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/xxx"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="禁用" />
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import Layout from '../components/Layout.vue'
import { toastError } from '../api/http'
import {
  listWebhooks, createWebhook, updateWebhook, deleteWebhook, setWebhookEnabled,
} from '../api/services'
import { fmtTime } from '../utils/status'

const loading = ref(false)
const saving = ref(false)
const togglingId = ref(null)
const rows = ref([])
const dialogVisible = ref(false)
const form = reactive(emptyForm())

function emptyForm() {
  return { id: null, name: '', type: 'FEISHU', url: '', enabled: true }
}

async function load() {
  loading.value = true
  try {
    rows.value = await listWebhooks()
  } catch (err) {
    toastError(err)
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, row ? {
    id: row.id, name: row.name, type: row.type, url: row.url, enabled: row.enabled,
  } : emptyForm())
  dialogVisible.value = true
}

async function onSave() {
  if (!form.name || !form.url) {
    ElMessage.warning('请填写名称和 Webhook 地址')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await updateWebhook(form.id, {
        name: form.name, type: form.type, url: form.url, enabled: form.enabled,
      })
    } else {
      await createWebhook({
        name: form.name, type: form.type, url: form.url, enabled: form.enabled,
      })
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

async function onToggle(row, val) {
  togglingId.value = row.id
  try {
    await setWebhookEnabled(row.id, val)
    row.enabled = val
    ElMessage.success(val ? '已启用' : '已禁用')
  } catch (err) {
    toastError(err)
  } finally {
    togglingId.value = null
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除 Webhook「${row.name}」？删除后相关事件将不再推送。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteWebhook(row.id)
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
.url-text {
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
</style>
