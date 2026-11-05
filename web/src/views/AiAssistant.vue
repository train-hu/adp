<template>
  <div class="chat-wrap">
      <!-- 左侧会话列表 -->
      <div class="side">
        <div class="side-head">
          <el-button type="primary" :icon="Plus" @click="newConversation">新对话</el-button>
        </div>
        <div class="conv-list" v-loading="convLoading">
          <div
            v-for="c in conversations"
            :key="c.conversationId"
            class="conv-item"
            :class="{ active: c.conversationId === currentId }"
            @click="switchConversation(c)"
          >
            <div class="conv-title">{{ c.lastMessage || '（空）' }}</div>
            <div class="conv-meta">{{ c.messageCount }} 条 · {{ c.lastAt }}</div>
          </div>
          <el-empty v-if="!convLoading && conversations.length === 0" description="暂无历史会话" :image-size="50" />
        </div>
      </div>

      <!-- 右侧对话主区 -->
      <div class="main">
        <div class="main-head">
          <span class="title">AI 助手</span>
          <el-select
            v-model="modelId"
            placeholder="选择模型（留空用默认）"
            clearable
            style="width: 240px"
          >
            <el-option v-for="m in models" :key="m.id" :label="`${m.name} · ${m.model}`" :value="m.id" />
          </el-select>
        </div>

        <div class="msg-list" ref="msgListRef" v-loading="chatLoading">
          <div v-for="(msg, i) in messages" :key="i" class="msg" :class="msg.role">
            <div class="bubble">
              <div class="role-tag">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
              <div v-if="msg.role === 'assistant'" class="markdown" v-html="renderMarkdown(msg.content)"></div>
              <div v-else class="text">{{ msg.content }}</div>
              <div v-if="msg.role === 'assistant' && msg.degraded" class="degraded">降级</div>
            </div>
          </div>
        </div>

        <div class="input-area">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="3"
            resize="none"
            placeholder="例如：帮我分析当前所有服务器的运行状态，并给出优化建议；或：搜索一下 Redis 的高可用配置文档"
            @keydown.enter.exact.prevent="onSend"
          />
          <div class="input-foot">
            <span class="hint">Enter 发送，Shift+Enter 换行</span>
            <el-button type="primary" :loading="chatLoading" :disabled="!draft.trim()" @click="onSend">
              发送
            </el-button>
          </div>
        </div>
      </div>
    </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { toastError } from '../api/http'
import {
  listLlmModels, chatAssistant, getAiHistory, getAiConversations,
} from '../api/services'

// ---- 会话数据 ----
const conversations = ref([])
const convLoading = ref(false)
const currentId = ref(null)
const messages = ref([])

// ---- 模型 ----
const models = ref([])
const modelId = ref(null)

// ---- 输入 ----
const draft = ref('')
const chatLoading = ref(false)
const msgListRef = ref(null)

const renderMarkdown = (text) => (text ? DOMPurify.sanitize(marked.parse(text)) : '')

async function loadModels() {
  try {
    models.value = await listLlmModels()
    const def = models.value.find((m) => m.isDefault)
    if (def) modelId.value = def.id
  } catch (e) {
    toastError(e)
  }
}

async function loadConversations() {
  convLoading.value = true
  try {
    conversations.value = await getAiConversations() || []
  } catch (e) {
    toastError(e)
  } finally {
    convLoading.value = false
  }
}

async function switchConversation(c) {
  currentId.value = c.conversationId
  chatLoading.value = true
  messages.value = []
  try {
    messages.value = await getAiHistory(c.conversationId) || []
    await scrollBottom()
  } catch (e) {
    toastError(e)
  } finally {
    chatLoading.value = false
  }
}

function newConversation() {
  currentId.value = null
  messages.value = []
  draft.value = ''
}

async function onSend() {
  const text = draft.value.trim()
  if (!text || chatLoading.value) return
  draft.value = ''
  messages.value.push({ role: 'user', content: text })
  chatLoading.value = true
  await scrollBottom()
  try {
    const res = await chatAssistant({
      conversationId: currentId.value || null,
      message: text,
      modelId: modelId.value || undefined,
    })
    currentId.value = res.conversationId
    messages.value.push({
      role: 'assistant',
      content: res.reply,
      degraded: res.degraded,
    })
    await loadConversations()
  } catch (e) {
    messages.value.push({ role: 'assistant', content: `请求失败：${e.message}`, degraded: true })
    toastError(e)
  } finally {
    chatLoading.value = false
    await scrollBottom()
  }
}

async function scrollBottom() {
  await nextTick()
  const el = msgListRef.value
  if (el) el.scrollTop = el.scrollHeight
}

onMounted(async () => {
  await Promise.all([loadModels(), loadConversations()])
  if (conversations.value.length > 0) {
    await switchConversation(conversations.value[0])
  }
})
</script>

<style scoped>
.chat-wrap {
  display: flex;
  gap: 16px;
  height: calc(100vh - 64px - 40px);
}

.side {
  width: 260px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}
.side-head {
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;
}
.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.conv-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.15s;
}
.conv-item:hover {
  background: #f5f7fa;
}
.conv-item.active {
  background: #ecf5ff;
}
.conv-title {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.conv-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}
.main-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid #f0f0f0;
}
.title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #fafafa;
}
.msg {
  display: flex;
  margin-bottom: 14px;
}
.msg.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  border: 1px solid #ebeef5;
}
.msg.user .bubble {
  background: #ecf5ff;
  border-color: #d9ecff;
}
.role-tag {
  font-size: 11px;
  color: #909399;
  margin-bottom: 4px;
}
.msg.user .role-tag {
  text-align: right;
  color: #409eff;
}
.text {
  white-space: pre-wrap;
  line-height: 1.6;
  padding: 4px 0;
}
.markdown {
  line-height: 1.7;
}
.degraded {
  margin-top: 6px;
  font-size: 11px;
  color: #e6a23c;
}
.input-area {
  border-top: 1px solid #f0f0f0;
  padding: 12px 16px;
}
.input-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.hint {
  font-size: 12px;
  color: #909399;
}
</style>