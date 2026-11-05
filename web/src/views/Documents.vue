<template>
  <Layout>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>
            部署文档
            <template v-if="selected">（部署 #{{ selected }}）</template>
          </span>
          <div class="doc-toolbar">
            <el-select v-model="selected" placeholder="选择部署任务" style="width: 220px" clearable @change="onSelect">
              <el-option
                v-for="d in deployments"
                :key="d.id"
                :label="`#${d.id} ${projectName(d.projectId)}（${deployStatus(d.status).label}）`"
                :value="d.id"
              />
            </el-select>
            <el-button v-if="selected && canOps" :icon="Refresh" :loading="regenerating" @click="onRegenerate">
              重新生成
            </el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" v-if="selected">
        <el-tab-pane label="部署文档" name="deploy" />
        <el-tab-pane label="维护文档" name="maintain" />
      </el-tabs>
      <div v-if="selected" class="doc-actions">
        <el-button type="primary" size="small" :icon="Download" @click="onDownload">下载 Markdown</el-button>
      </div>

      <el-empty v-if="!selected" description="请从上方选择一个部署任务查看文档" />
      <div v-else class="markdown-body" v-loading="docLoading" v-html="rendered"></div>
      <el-empty v-if="selected && !docLoading && !rendered"
                description="该部署暂无文档（部署成功后自动生成）" :image-size="60" />
    </el-card>
  </Layout>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Download } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import Layout from '../components/Layout.vue'
import { hasRole, toastError, downloadBlob } from '../api/http'
import {
  listDeployments, listProjects, getDocument, downloadDocument, regenerateDocument,
} from '../api/services'
import { deployStatus } from '../utils/status'

const route = useRoute()
const canOps = computed(() => hasRole('OPS'))

const deployments = ref([])
const projects = ref([])
const selected = ref(null)
const activeTab = ref('deploy')
const docContent = ref('')
const docLoading = ref(false)
const regenerating = ref(false)

const rendered = computed(() =>
  docContent.value ? DOMPurify.sanitize(marked.parse(docContent.value)) : '',
)

function projectName(id) {
  return projects.value.find((p) => p.id === id)?.name || `#${id}`
}

async function loadDeployments() {
  try {
    const [deps, ps] = await Promise.all([listDeployments(), listProjects()])
    deployments.value = deps
    projects.value = ps
    const q = Number(route.query.deployment) || null
    if (q && deps.some((d) => d.id === q)) {
      selected.value = q
      await onSelect(q)
    }
  } catch (err) {
    toastError(err)
  }
}

async function onSelect(id) {
  if (!id) return
  docContent.value = ''
  docLoading.value = true
  try {
    docContent.value = await getDocument(id, activeTab.value)
  } catch (err) {
    toastError(err)
  } finally {
    docLoading.value = false
  }
}

// 切换 tab 时重新拉取对应文档
watch(activeTab, () => {
  if (selected.value) onSelect(selected.value)
})

async function onRegenerate() {
  regenerating.value = true
  try {
    await regenerateDocument(selected.value)
    ElMessage.success('文档已重新生成')
    await onSelect(selected.value)
  } catch (err) {
    toastError(err)
  } finally {
    regenerating.value = false
  }
}

async function onDownload() {
  try {
    await downloadBlob(`/deployments/${selected.value}/documents/download?type=${activeTab.value}`, `doc-${activeTab.value}-${selected.value}.md`)
  } catch (err) {
    toastError(err)
  }
}

onMounted(loadDeployments)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.doc-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.doc-actions {
  margin: 8px 0;
}
.markdown-body {
  min-height: 200px;
  line-height: 1.7;
}
</style>
