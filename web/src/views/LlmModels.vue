<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>LLM 模型配置（OpenAI 兼容协议，支持云端 API 与本地私有化部署）</span>
          <el-button v-if="canAdmin" type="primary" :icon="Plus" @click="openCreate">新增模型</el-button>
        </div>
      </template>

      <el-table :data="models" v-loading="loading">
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column prop="baseUrl" label="API 地址" min-width="240" show-overflow-tooltip />
        <el-table-column prop="model" label="模型" width="180" />
        <el-table-column prop="apiKeyMask" label="API Key（脱敏）" width="180" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :loading="testingId === row.id" @click="onTest(row)">连接测试</el-button>
            <el-button size="small" :icon="ChatDotRound" @click="openChat(row)">对话测试</el-button>
            <el-button v-if="canAdmin" size="small" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canAdmin" size="small" type="danger" :icon="Delete" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无模型配置，点击右上角「新增模型」接入（云端 API 或本地服务器均可）" :image-size="60" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑模型' : '新增模型'" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="!!editingId" placeholder="如 deepseek-v4" maxlength="64" />
        </el-form-item>
        <el-form-item label="API 地址" required>
          <el-input v-model="form.baseUrl" placeholder="如 https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item label="模型 ID" required>
          <el-input v-model="form.model" placeholder="如 deepseek-v4 / qwen3-32b / gpt-4o" maxlength="128" />
        </el-form-item>
        <el-form-item label="API Key" :required="!editingId">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="editingId ? '留空表示不修改' : 'sk-...'" />
        </el-form-item>
        <el-form-item v-if="editingId" label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 对话测试对话框 -->
    <el-dialog v-model="chatVisible" title="对话测试" width="560px">
      <div v-if="chatTarget">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px">
          向 <b>{{ chatTarget.name }}</b>（{{ chatTarget.model }}）发送一条测试消息
        </el-alert>
        <el-input v-model="chatPrompt" type="textarea" :rows="3" placeholder="输入测试提示词（默认：请回复 ok 以确认模型可用）" />
        <el-button type="primary" :loading="chatLoading" style="margin-top: 12px" @click="onChat">发送</el-button>
        <el-input v-if="chatReply" v-model="chatReply" type="textarea" :rows="8" readonly style="margin-top: 12px" />
      </div>
    </el-dialog>
  </Layout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, ChatDotRound } from '@element-plus/icons-vue'
import Layout from '../components/Layout.vue'
import { hasRole, toastError } from '../api/http'
import {
  listLlmModels, createLlmModel, updateLlmModel, deleteLlmModel, testLlmModel, chatTestLlmModel,
} from '../api/services'

const canAdmin = computed(() => hasRole('ADMIN'))

const models = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    models.value = await listLlmModels()
  } catch (e) {
    toastError(e)
  } finally {
    loading.value = false
  }
}

// ---- 新增/编辑 ----
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = ref({ name: '', baseUrl: '', model: '', apiKey: '', enabled: true })

function openCreate() {
  editingId.value = null
  form.value = { name: '', baseUrl: '', model: '', apiKey: '', enabled: true }
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = { name: row.name, baseUrl: row.baseUrl, model: row.model, apiKey: '', enabled: row.enabled }
  dialogVisible.value = true
}

async function onSave() {
  if (!form.value.name || !form.value.baseUrl || !form.value.model) {
    ElMessage.warning('名称 / API 地址 / 模型 ID 必填')
    return
  }
  if (!editingId.value && !form.value.apiKey) {
    ElMessage.warning('API Key 必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateLlmModel(editingId.value, {
        baseUrl: form.value.baseUrl, model: form.value.model,
        apiKey: form.value.apiKey || undefined, enabled: form.value.enabled,
      })
      ElMessage.success('已保存')
    } else {
      await createLlmModel({ ...form.value, apiKey: form.value.apiKey })
      ElMessage.success('已创建，建议先做连接测试')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    toastError(e)
  } finally {
    saving.value = false
  }
}

// ---- 删除 ----
async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除模型「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteLlmModel(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    toastError(e)
  }
}

// ---- 连接测试 ----
const testingId = ref(null)
async function onTest(row) {
  testingId.value = row.id
  try {
    const r = await testLlmModel(row.id)
    if (r.ok) {
      ElMessage.success(`连接成功，可用模型：${(r.availableModels || []).join(', ') || '（列表为空）'}`)
    } else {
      ElMessage.error(`连接失败：${r.message}`)
    }
  } catch (e) {
    toastError(e)
  } finally {
    testingId.value = null
  }
}

// ---- 对话测试 ----
const chatVisible = ref(false)
const chatLoading = ref(false)
const chatTarget = ref(null)
const chatPrompt = ref('')
const chatReply = ref('')

function openChat(row) {
  chatTarget.value = row
  chatPrompt.value = ''
  chatReply.value = ''
  chatVisible.value = true
}

async function onChat() {
  chatLoading.value = true
  chatReply.value = ''
  try {
    chatReply.value = await chatTestLlmModel(chatTarget.value.id, chatPrompt.value) || '（模型返回空内容）'
  } catch (e) {
    toastError(e)
  } finally {
    chatLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
