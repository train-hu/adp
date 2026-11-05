<template>
  <Layout>
    <el-row :gutter="12">
      <el-col :span="9">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>项目列表（{{ projects.length }}）</span>
              <el-button
                v-if="canOps"
                type="primary"
                size="small"
                :icon="Plus"
                @click="openProjectDialog(null)"
              >
                新建项目
              </el-button>
            </div>
          </template>
          <el-table :data="projects" v-loading="projectsLoading" border highlight-current-row
                    @current-change="selectProject">
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="name" label="名称" min-width="110" />
            <el-table-column label="识别类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="info">{{ row.detectedType || '未知' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="默认端口" width="80">
              <template #default="{ row }">{{ row.defaultPort || '-' }}</template>
            </el-table-column>
            <el-table-column label="成员" width="70">
              <template #default="{ row }">{{ memberCount(row.id) }}</template>
            </el-table-column>
            <el-table-column v-if="canAdmin || projects.some((p) => p.isMember)" label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canManageProject(row)" type="danger" link size="small" :icon="Delete" @click.stop="onDeleteProject(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="15">
        <div v-if="currentProject" class="service-bar">
          <div class="service-tabs">
            <span class="service-bar-label">服务：</span>
            <el-radio-group :model-value="currentServiceId" size="small" @change="onSwitchService">
              <el-radio-button
                v-for="s in subServices"
                :key="s.id"
                :value="s.id"
              >
                {{ s.name }}<el-tag v-if="s.defaultService" size="small" type="info" style="margin-left: 4px">默认</el-tag>
              </el-radio-button>
            </el-radio-group>
          </div>
          <div v-if="canManage" class="service-actions">
            <el-button v-if="currentProject.multiService" size="small" :icon="Plus" @click="openSubServiceDialog">新建服务</el-button>
            <el-button
              v-if="currentService && !currentService.defaultService"
              size="small"
              type="danger"
              link
              :icon="Delete"
              @click="onDeleteSubService(currentService)"
            >
              删除当前服务
            </el-button>
          </div>
        </div>

        <el-card>
          <template #header>
            <div class="card-header">
              <span>
                部署包版本
                <template v-if="currentProject">
                  —— {{ currentProject.name }}
                  <el-tag size="small" type="info" style="margin-left: 6px">{{ currentProject.detectedType || '未知' }}</el-tag>
                </template>
                <template v-else>（请从左侧选择项目）</template>
              </span>
              <el-button
                v-if="currentProject && canOps && currentService"
                type="primary"
                size="small"
                :icon="Upload"
                @click="openUpload"
              >
                上传部署包
              </el-button>
            </div>
          </template>

          <el-alert
            v-if="currentProject?.multiService && !subServices.length"
            title="多服务项目尚未创建任何服务，请先在上方创建服务（填写启动命令和端口），再上传部署包。"
            type="info"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          />
          <el-table :data="versions" v-loading="versionsLoading" border>
            <el-table-column prop="id" label="版本ID" width="70" />
            <el-table-column prop="fileName" label="文件名" min-width="150" show-overflow-tooltip />
            <el-table-column label="来源" width="130">
              <template #default="{ row }">
                <el-tooltip v-if="row.gitUrl" :content="`${row.gitUrl}${row.gitRef ? ' (' + row.gitRef + ')' : ''}\ncommit: ${row.gitCommit}`" placement="top">
                  <el-tag size="small" type="warning">
                    git{{ row.gitCommit ? ' · ' + row.gitCommit.slice(0, 7) : '' }}
                  </el-tag>
                </el-tooltip>
                <el-tag v-else size="small" type="info">上传</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="100">
              <template #default="{ row }">{{ fmtSize(row.sizeBytes) }}</template>
            </el-table-column>
            <el-table-column prop="versionNote" label="版本说明" min-width="110" show-overflow-tooltip />
            <el-table-column label="识别类型" width="90">
              <template #default="{ row }">
                <el-tag size="small">{{ row.detectedType || '未知' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="上传时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canOps" link type="primary" size="small" @click="onDownload(row)">
                  下载
                </el-button>
                <el-button v-if="canOps" link type="success" size="small" @click="onDeploy(row)">
                  发起部署
                </el-button>
                <el-button v-if="canManage" link type="danger" size="small" @click="onDeleteVersion(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card v-if="currentProject" class="config-card">
          <template #header>
            <div class="card-header">
              <span>
                配置文件
                <el-tooltip
                  content="预设：nginx 模式读 nginx；DOCKER 模式读 compose 与 env。自定义 fileKey 支持相对路径（如 conf/config.toml）：PROCESS 模式解压后按该路径覆盖包内文件，实现按环境定制配置；也可点「从包导入」从部署包里挑文件作为覆盖基准。"
                  placement="top"
                >
                  <el-icon style="vertical-align: -2px"><InfoFilled /></el-icon>
                </el-tooltip>
              </span>
              <div style="display: flex; gap: 8px">
                <el-button
                  v-if="canManage && versions.length"
                  size="small"
                  :icon="FolderOpened"
                  @click="openImportDialog"
                >
                  从包导入
                </el-button>
                <el-button
                  v-if="canManage && currentService"
                  size="small"
                  type="primary"
                  :icon="Plus"
                  @click="openConfigDialog(null)"
                >
                  添加配置
                </el-button>
              </div>
            </div>
          </template>
          <el-table :data="configFiles" v-loading="configsLoading" border>
            <el-table-column label="配置" width="180">
              <template #default="{ row }">
                <el-tag size="small" :type="isPathKey(row.fileKey) ? 'success' : 'primary'">
                  {{ configLabel(row.fileKey) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="fileKey / 包内路径" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cfg-key">{{ row.fileKey }}</span>
                <el-tooltip v-if="isPathKey(row.fileKey)"
                            :content="`PROCESS 模式解压后覆盖 ${row.fileKey}`"
                            placement="top">
                  <el-tag size="small" type="warning" style="margin-left: 6px">覆盖包内文件</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="可用性" min-width="120">
              <template #default="{ row }">
                <el-tooltip v-if="row.warning" :content="row.warning" placement="top">
                  <el-tag size="small" type="warning">校验告警</el-tag>
                </el-tooltip>
                <el-tag v-else size="small" type="success">可用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canManage" link type="primary" size="small" @click="openConfigDialog(row)">
                  编辑
                </el-button>
                <el-button v-if="canManage" link type="danger" size="small" @click="onDeleteConfig(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card v-if="currentProject" class="config-card">
          <template #header>
            <div class="card-header">
              <span>
                项目成员
                <el-tooltip
                  content="成员即可完全管理项目（删项目 / 改配置 / 管理成员 / 上传包 / 部署）；全局角色需 &ge; OPS。管理员对全部项目拥有成员权限。"
                  placement="top"
                >
                  <el-icon style="vertical-align: -2px"><InfoFilled /></el-icon>
                </el-tooltip>
              </span>
              <el-button
                v-if="canManage"
                size="small"
                :icon="Plus"
                @click="openMemberDialog"
              >
                添加成员
              </el-button>
            </div>
          </template>
          <el-table :data="members" v-loading="membersLoading" border>
            <el-table-column label="用户" min-width="120">
              <template #default="{ row }">
                {{ row.username }}
                <el-tag v-if="row.username === currentUsername" size="small" type="info" style="margin-left: 4px">我</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="加入时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.joinedAt) }}</template>
            </el-table-column>
            <el-table-column v-if="canManage" label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="danger"
                  size="small"
                  @click="onRemoveMember(row)"
                >
                  移除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 新建/编辑项目 -->
    <el-dialog v-model="projectDialog" :title="projectForm.id ? '编辑项目' : '新建项目'" width="520">
      <el-form :model="projectForm" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="projectForm.name" placeholder="项目名（唯一）" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="projectForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="多个服务">
          <el-switch v-model="projectForm.multiService" :disabled="!!projectForm.id" />
          <span style="margin-left: 8px; font-size: 12px; color: #909399">
            多服务项目不自动创建默认服务，启动命令和端口在新建服务时填写
          </span>
        </el-form-item>
        <el-form-item v-if="!projectForm.multiService" label="默认启动命令">
          <el-input v-model="projectForm.defaultStartCmd" placeholder="如 java -jar app.jar（可留空由识别决定）" />
        </el-form-item>
        <el-form-item v-if="!projectForm.multiService" label="默认端口">
          <el-input-number v-model="projectForm.defaultPort" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="默认健康检查路径">
          <el-input v-model="projectForm.defaultHealthPath" placeholder="如 /health" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDialog = false">取消</el-button>
        <el-button type="primary" :loading="projectSaving" @click="onSaveProject">保存</el-button>
      </template>
    </el-dialog>

    <!-- 上传部署包 -->
    <el-dialog v-model="uploadDialog" title="上传部署包" width="520">
      <el-form label-width="90px">
        <el-form-item label="目标项目">
          <el-input :model-value="currentProject?.name" disabled />
        </el-form-item>
        <el-form-item label="目标服务">
          <el-input :model-value="currentService ? currentService.name : '（未选择服务）'" disabled />
        </el-form-item>
        <el-form-item label="来源" required>
          <el-radio-group v-model="uploadMode">
            <el-radio-button value="file">上传文件</el-radio-button>
            <el-radio-button value="git">git 拉取</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="uploadMode === 'file'" label="文件" required>
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-exceed="() => ElMessage.warning('一次只能上传一个文件')"
            accept=".zip,.jar,.html,.htm,.tar.gz,.tgz"
          >
            <el-button :icon="Upload">选择文件</el-button>
            <template #tip>
              <div class="el-upload__tip">支持 zip / html / jar / tar.gz 等，单文件 ≤ 2GB（纯 html 静态站免启动命令）</div>
            </template>
          </el-upload>
        </el-form-item>
        <template v-else>
          <el-form-item label="git 地址" required>
            <el-input v-model="gitForm.gitUrl" placeholder="https://git.example.com/group/repo.git" />
          </el-form-item>
          <el-form-item label="分支/标签">
            <el-input v-model="gitForm.gitRef" placeholder="留空使用默认分支 HEAD（如 main / v1.0.0）" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="gitForm.gitUsername" placeholder="私有仓库填写（公开仓库留空）" />
          </el-form-item>
          <el-form-item label="密码/令牌">
            <el-input v-model="gitForm.gitPassword" type="password" show-password
                     placeholder="私有仓库密码或 access token" />
          </el-form-item>
          <el-form-item label="说明">
            <span style="font-size: 12px; color: #909399">平台侧 clone 后打 zip 走标准部署链路，上传时冻结 commit（重复拉取同 commit 会提示内容已存在）</span>
          </el-form-item>
        </template>
        <el-form-item label="版本说明">
          <el-input v-model="versionNote" placeholder="如 v1.0.0（可选，≤255 字符）" />
        </el-form-item>
        <el-form-item v-if="uploadMode === 'file' && uploadChunkProgress.total > 0">
          <el-progress :percentage="chunkPercent" :stroke-width="14" striped :striped-flow="uploading" />
          <div style="font-size: 12px; color: #909399; margin-top: 6px">
            分片上传 {{ uploadChunkProgress.uploaded }} / {{ uploadChunkProgress.total }}
            <template v-if="uploadChunkResumed > 0">（断点续传，已跳过 {{ uploadChunkResumed }} 片）</template>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialog = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="onUpload">{{ uploadMode === 'git' ? '拉取并创建版本' : '上传' }}</el-button>
      </template>
    </el-dialog>

    <!-- 编辑/新增配置文件 -->
    <el-dialog v-model="configDialog" :title="configDialogTitle" width="760" top="6vh">
      <el-form label-width="90px">
        <el-form-item label="配置项" required>
          <el-select v-model="configForm.fileKey" :disabled="!!configForm.id" style="width: 280px">
            <el-option label="nginx.conf（nginx 模式站点配置）" value="nginx" />
            <el-option label="docker-compose.yml（DOCKER 模式）" value="compose" />
            <el-option label=".env（环境变量，配合 compose）" value="env" />
            <el-option label="自定义 / 包内文件路径（如 conf/config.toml）" value="__custom__" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="configForm.fileKey === '__custom__'" label="fileKey" required>
          <el-input
            v-model="configForm.customKey"
            placeholder="支持相对路径，如 conf/config.toml（PROCESS 模式解压后覆盖该包内文件）"
            style="width: 280px"
          />
        </el-form-item>
        <el-form-item v-if="configForm.fileKey === '__custom__' && isPathKey(configForm.customKey)" label="覆盖说明">
          <span style="font-size: 12px; color: #e6a23c">
            PROCESS 模式部署时，解压完成后会按此相对路径覆盖包内同名文件（{{ configForm.customKey }}）。
          </span>
        </el-form-item>
        <el-form-item v-if="['nginx', 'compose', 'env'].includes(configForm.fileKey)" label="下发路径">
          <el-input
            v-model="configForm.remotePath"
            placeholder="可选：目标机上的落盘路径，留空用默认（compose/.env → workdir，nginx → /etc/nginx/conf.d/）"
          />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="configForm.content"
            type="textarea"
            :rows="16"
            placeholder="粘贴文件全文（或点「从包导入」预填包内同名文件）"
            class="config-textarea"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialog = false">取消</el-button>
        <el-button v-if="canManage" type="primary" :loading="configSaving" @click="onSaveConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 从部署包导入配置文件 -->
    <el-dialog v-model="importDialog" title="从部署包导入配置文件" width="820" top="5vh">
      <el-form label-width="90px">
        <el-form-item label="部署包">
          <el-select v-model="importForm.versionId" filterable style="width: 100%" @change="onImportVersionChange">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`${v.fileName}（v${v.id}）`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-divider style="margin: 6px 0 10px" />
      <div class="import-hint">
        点选包内文件预览内容，确认后作为该相对路径的配置覆盖项写入（PROCESS 模式部署时覆盖包内同名文件）。
      </div>
      <el-row :gutter="12">
        <el-col :span="9">
          <el-tree
            ref="importTreeRef"
            :data="importTree"
            :props="{ label: 'label', children: 'children' }"
            node-key="path"
            highlight-current
            :expand-on-click-node="false"
            default-expand-all
            @node-click="onImportTreeNodeClick"
            v-loading="importTreeLoading"
          >
            <template #default="{ data }">
              <span class="import-node" :class="{ 'is-dir': data.isDir }">
                <el-icon v-if="data.isDir" style="vertical-align: -2px; margin-right: 4px"><Folder /></el-icon>
                <el-icon v-else style="vertical-align: -2px; margin-right: 4px"><Document /></el-icon>
                {{ data.label }}
              </span>
            </template>
          </el-tree>
        </el-col>
        <el-col :span="15">
          <el-card v-if="importForm.selectedPath" shadow="never" class="import-preview-card">
            <template #header>
              <div class="card-header">
                <span class="cfg-key">{{ importForm.selectedPath }}</span>
                <el-button type="primary" size="small" @click="onImportConfirm">导入为配置项</el-button>
              </div>
            </template>
            <pre class="import-preview" v-loading="importPreviewLoading">{{ importForm.previewContent || '（无内容或读取失败）' }}</pre>
          </el-card>
          <el-empty v-else description="点选左侧包内文件查看内容" :image-size="80" />
        </el-col>
      </el-row>
      <template #footer>
        <el-button @click="importDialog = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 添加成员 -->
    <el-dialog v-model="memberDialog" title="添加项目成员" width="440">
      <el-form :model="memberForm" label-width="90px">
        <el-form-item label="用户" required>
          <el-select v-model="memberForm.userId" placeholder="选择用户" filterable style="width: 100%">
            <el-option
              v-for="u in addableUsers"
              :key="u.id"
              :label="`${u.username}（${u.role}）`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="权限说明">
          <span style="font-size: 12px; color: #909399">成员即可完全管理该项目（项目内无角色分级）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialog = false">取消</el-button>
        <el-button type="primary" :loading="memberSaving" @click="onSaveMember">添加</el-button>
      </template>
    </el-dialog>

    <!-- 新建子服务 -->
    <el-dialog v-model="subServiceDialog" title="新建服务" width="460">
      <el-form :model="subServiceForm" label-width="90px">
        <el-form-item label="所属项目">
          <el-input :model-value="currentProject?.name" disabled />
        </el-form-item>
        <el-form-item label="服务名" required>
          <el-input v-model="subServiceForm.name" placeholder="如 grafana / api / worker（项目内唯一）" maxlength="64" />
        </el-form-item>
        <el-form-item label="启动命令">
          <el-input v-model="subServiceForm.startCmd" placeholder="如 java -jar app.jar（选填，可留空由识别决定）" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="subServiceForm.port" :min="1" :max="65535" controls-position="right" style="width: 180px" placeholder="选填，如 8080" />
        </el-form-item>
        <el-form-item label="说明">
          <span style="font-size: 12px; color: #909399">
            服务名会生成路径 slug（小写字母/数字/中划线）：部署目录
            /opt/deploy/{项目}/{slug}/{版本}，systemd unit deploy-{项目}-{slug}.service，与默认服务互不覆盖。
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="subServiceDialog = false">取消</el-button>
        <el-button type="primary" :loading="subServiceSaving" @click="onCreateSubService">创建</el-button>
      </template>
    </el-dialog>
  </Layout>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload, InfoFilled, Delete, FolderOpened, Folder, Document } from '@element-plus/icons-vue'
import Layout from '../components/Layout.vue'
import http, { hasRole, toastError, downloadBlob, getUsername } from '../api/http'
import {
  listProjects, createProject, updateProject, deleteProject,
  listVersions, deleteVersion,
  listConfigFiles, saveConfigFile, deleteConfigFile,
  listSubServices, createSubService, deleteSubService,
  getPackageTree, getPackageFile,
  listServiceInstances,
  listProjectMembers, addProjectMember, removeProjectMember,
  listMemberCandidates,
  initChunkUpload, uploadChunk, getUploadProgress, completeChunkUpload,
} from '../api/services'
import { fmtTime } from '../utils/status'

const router = useRouter()
const canAdmin = computed(() => hasRole('ADMIN'))
const canOps = computed(() => hasRole('OPS'))
const currentUsername = getUsername()

const projects = ref([])
const projectsLoading = ref(false)
const currentProject = ref(null)
const versions = ref([])
const versionsLoading = ref(false)

const projectDialog = ref(false)
const projectSaving = ref(false)
const projectForm = reactive(emptyProject())

const uploadDialog = ref(false)
const uploading = ref(false)
const versionNote = ref('')
const pickedFile = ref(null)
const uploadRef = ref(null)
const uploadMode = ref('file') // 'file' | 'git'
// 分片上传进度（>10MB 文件走分片链路，支持断点续传）
const CHUNK_SIZE = 10 * 1024 * 1024
const CHUNK_THRESHOLD = 10 * 1024 * 1024 // 超过 10MB 走分片上传
const uploadChunkProgress = reactive({ uploaded: 0, total: 0 })
const uploadChunkResumed = ref(0)
// 断点续传：同一文件（名称+大小）复用未完成会话；仅记录在内存，页面刷新后重传
let activeSession = null // { key, sessionId, fileName, totalSize, total }
const chunkPercent = computed(() =>
  uploadChunkProgress.total
    ? Math.min(100, Math.round((uploadChunkProgress.uploaded / uploadChunkProgress.total) * 100))
    : 0,
)
const gitForm = reactive({ gitUrl: '', gitRef: '', gitUsername: '', gitPassword: '' })

const configFiles = ref([])
const configsLoading = ref(false)
const configDialog = ref(false)
const configSaving = ref(false)
const configForm = reactive({ id: null, fileKey: '', customKey: '', remotePath: '', content: '' })

// ---------- 从部署包导入配置（目录树浏览 + 预览 + 一键写入覆盖项） ----------
const importDialog = ref(false)
const importTreeLoading = ref(false)
const importPreviewLoading = ref(false)
const importTree = ref([])
const importTreeRef = ref(null)
const importForm = reactive({
  versionId: null,
  selectedPath: '',
  previewContent: '',
})

const members = ref([])
const membersLoading = ref(false)
const allUsers = ref([])
const memberDialog = ref(false)
const memberSaving = ref(false)
const memberForm = reactive({ userId: null })

// ---------- 子服务（1 项目 N 服务：包/配置/部署按当前服务作用域） ----------
const subServices = ref([])
const currentService = ref(null)
const currentServiceId = computed(() => currentService.value?.id ?? null)
const subServiceDialog = ref(false)
const subServiceSaving = ref(false)
const subServiceForm = reactive({ name: '', startCmd: '', port: null })

const CONFIG_LABELS = {
  nginx: 'nginx.conf',
  compose: 'docker-compose.yml',
  env: '.env',
}
function configLabel(fileKey) {
  return CONFIG_LABELS[fileKey] || fileKey
}

// 相对路径型 fileKey（含 '/'）：PROCESS 模式解压后按该路径覆盖包内文件
function isPathKey(fileKey) {
  return !!fileKey && fileKey.includes('/')
}

function emptyProject() {
  return { id: null, name: '', description: '', multiService: false, defaultStartCmd: '', defaultPort: null, defaultHealthPath: '' }
}

function fmtSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

async function loadProjects() {
  projectsLoading.value = true
  try {
    projects.value = await listProjects()
  } catch (err) {
    toastError(err)
  } finally {
    projectsLoading.value = false
  }
}

async function selectProject(row) {
  if (!row) return
  currentProject.value = row
  currentService.value = null
  subServices.value = []
  versions.value = []
  configFiles.value = []
  await Promise.all([loadSubServices(), loadMembers()])
}

async function loadSubServices() {
  if (!currentProject.value) return
  try {
    subServices.value = await listSubServices(currentProject.value.id)
    // 默认选首个服务（后端默认服务排前）
    const fallback = subServices.value.find((s) => s.defaultService) || subServices.value[0] || null
    currentService.value = subServices.value.find((s) => s.id === fallback?.id) || null
    await Promise.all([loadVersions(), loadConfigFiles()])
  } catch (err) {
    toastError(err)
  }
}

function onSwitchService(serviceId) {
  currentService.value = subServices.value.find((s) => s.id === serviceId) || null
  Promise.all([loadVersions(), loadConfigFiles()])
}

function openSubServiceDialog() {
  subServiceForm.name = ''
  subServiceForm.startCmd = ''
  subServiceForm.port = null
  subServiceDialog.value = true
}

async function onCreateSubService() {
  const name = subServiceForm.name.trim()
  if (!name) {
    ElMessage.warning('请填写服务名')
    return
  }
  const startCmd = subServiceForm.startCmd.trim()
  subServiceSaving.value = true
  try {
    const created = await createSubService(currentProject.value.id, {
      name,
      startCmd: startCmd || undefined,
      port: subServiceForm.port || undefined,
    })
    ElMessage.success('服务已创建')
    subServiceDialog.value = false
    await loadSubServices()
    // 切到新服务，便于直接上传/配置
    const switched = subServices.value.find((s) => s.id === created?.id)
    if (switched) {
      currentService.value = switched
      await Promise.all([loadVersions(), loadConfigFiles()])
    }
  } catch (err) {
    toastError(err)
  } finally {
    subServiceSaving.value = false
  }
}

async function onDeleteSubService(s) {
  try {
    await ElMessageBox.confirm(
      `确定删除服务「${s.name}」？其名下全部部署包版本、配置文件与部署记录将被级联删除（不删除目标机磁盘文件），此操作不可恢复。`,
      '删除服务',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteSubService(currentProject.value.id, s.id)
    ElMessage.success('服务已删除')
    const wasCurrent = currentService.value?.id === s.id
    await loadSubServices()
    if (wasCurrent && !currentService.value) {
      await Promise.all([loadVersions(), loadConfigFiles()])
    }
  } catch (err) {
    toastError(err)
  }
}

async function loadVersions() {
  if (!currentProject.value) return
  // 未选中服务（多服务项目尚无服务）：不请求，避免后端 NOT_FOUND 报错
  if (!currentServiceId.value) {
    versions.value = []
    return
  }
  versionsLoading.value = true
  try {
    versions.value = await listVersions(currentProject.value.id, currentServiceId.value)
  } catch (err) {
    toastError(err)
  } finally {
    versionsLoading.value = false
  }
}

async function loadConfigFiles() {
  if (!currentProject.value) return
  if (!currentServiceId.value) {
    configFiles.value = []
    return
  }
  configsLoading.value = true
  try {
    configFiles.value = await listConfigFiles(currentProject.value.id, currentServiceId.value)
  } catch (err) {
    toastError(err)
  } finally {
    configsLoading.value = false
  }
}

// ---------- 项目成员管理（ADMIN 或项目成员，成员即可完全管理） ----------

const canManage = computed(() => {
  if (!currentProject.value) return false
  // 全局 ADMIN 恒可管理；项目成员（isMember）即可完全管理
  return canAdmin.value || currentProject.value.isMember
})

// 行级：某项目是否可被当前用户管理（用于项目列表"删除"按钮按行显隐）
function canManageProject(row) {
  return canAdmin.value || row.isMember
}

// 各项目的成员数（选中加载过才有值，未加载显示 '-'，避免 N+1）
const memberCounts = reactive({})
function memberCount(id) {
  return memberCounts[id] != null ? memberCounts[id] : '-'
}

async function loadMembers() {
  if (!currentProject.value) return
  membersLoading.value = true
  try {
    members.value = await listProjectMembers(currentProject.value.id)
    memberCounts[currentProject.value.id] = members.value.length
  } catch (err) {
    toastError(err)
  } finally {
    membersLoading.value = false
  }
}

async function openMemberDialog() {
  Object.assign(memberForm, { userId: null })
  memberDialog.value = true
  // 打开时拉取候选用户（可管理项目者才能访问该端点；非 ADMIN 的项目成员也能用）
  try {
    allUsers.value = await listMemberCandidates(currentProject.value.id)
  } catch (err) {
    toastError(err)
    memberDialog.value = false
  }
}

async function onSaveMember() {
  if (!memberForm.userId) {
    ElMessage.warning('请选择用户')
    return
  }
  memberSaving.value = true
  try {
    await addProjectMember(currentProject.value.id, {
      userId: memberForm.userId,
    })
    ElMessage.success('成员已添加')
    memberDialog.value = false
    await loadMembers()
  } catch (err) {
    toastError(err)
  } finally {
    memberSaving.value = false
  }
}

async function onRemoveMember(row) {
  try {
    await ElMessageBox.confirm(
      `确定将用户「${row.username}」移出项目「${currentProject.value.name}」？`,
      '移除成员',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await removeProjectMember(currentProject.value.id, row.userId)
    ElMessage.success('已移除')
    await loadMembers()
  } catch (err) {
    toastError(err)
  }
}

// 添加成员候选列表：所有用户 - 已是成员的
const addableUsers = computed(() => {
  const memberIds = new Set(members.value.map((m) => m.userId))
  return allUsers.value.filter((u) => !memberIds.has(u.id))
})

const configDialogTitle = computed(() => (configForm.id ? `编辑配置 · ${configForm.fileKey}` : '新增配置'))

function openConfigDialog(row) {
  if (row) {
    // 路径型 fileKey（含 '/'）归到「自定义」选项，让输入框回显完整相对路径
    const pathKey = isPathKey(row.fileKey)
    Object.assign(configForm, {
      id: row.id,
      fileKey: pathKey ? '__custom__' : row.fileKey,
      customKey: pathKey ? row.fileKey : '',
      remotePath: row.remotePath || '', content: row.content || '',
    })
  } else {
    Object.assign(configForm, { id: null, fileKey: 'nginx', customKey: '', remotePath: '', content: '' })
  }
  configDialog.value = true
}

async function onSaveConfig() {
  const fileKey = configForm.fileKey === '__custom__' ? configForm.customKey.trim() : configForm.fileKey
  if (!fileKey || !/^[a-z0-9][a-z0-9._/-]*$/.test(fileKey)) {
    ElMessage.warning('fileKey 需小写字母/数字开头，可含 . _ - /（含 / 视为包内相对路径）')
    return
  }
  if (!configForm.content.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  configSaving.value = true
  try {
    // 路径型 fileKey 的下发目标由 fileKey 本身决定（解压后 {extract}/{fileKey}），不再附带 remotePath
    const payload = {
      fileKey,
      content: configForm.content,
    }
    if (!isPathKey(fileKey)) {
      payload.remotePath = configForm.remotePath || undefined
    }
    await saveConfigFile(currentProject.value.id, payload, currentServiceId.value)
    ElMessage.success('配置已保存')
    configDialog.value = false
    await loadConfigFiles()
  } catch (err) {
    toastError(err)
  } finally {
    configSaving.value = false
  }
}

async function onDeleteConfig(row) {
  try {
    await ElMessageBox.confirm(`确定删除配置「${configLabel(row.fileKey)}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteConfigFile(currentProject.value.id, row.fileKey, currentServiceId.value)
    ElMessage.success('已删除')
    await loadConfigFiles()
  } catch (err) {
    toastError(err)
  }
}

// ---------- 从部署包导入配置 ----------

function openImportDialog() {
  importTree.value = []
  importForm.selectedPath = ''
  importForm.previewContent = ''
  importForm.versionId = versions.value.length ? versions.value[0].id : null
  importDialog.value = true
  if (importForm.versionId) {
    loadImportTree(importForm.versionId)
  }
}

async function onImportVersionChange(versionId) {
  importForm.selectedPath = ''
  importForm.previewContent = ''
  if (versionId) {
    await loadImportTree(versionId)
  } else {
    importTree.value = []
  }
}

async function loadImportTree(versionId) {
  importTreeLoading.value = true
  importTree.value = []
  try {
    const entries = await getPackageTree(versionId)
    importTree.value = buildTree(entries || [])
  } catch (err) {
    toastError(err)
  } finally {
    importTreeLoading.value = false
  }
}

// 把相对路径清单（目录带 '/' 尾缀）组装成 el-tree 结构
function buildTree(entries) {
  const root = { children: [] }
  for (const entry of entries) {
    const isDir = entry.endsWith('/')
    const clean = entry.replace(/\/+$/, '')
    if (!clean) continue
    const parts = clean.split('/')
    let node = root
    for (let i = 0; i < parts.length; i++) {
      const name = parts[i]
      const isLast = i === parts.length - 1
      const fullPath = parts.slice(0, i + 1).join('/')
      let child = node.children.find((c) => c.name === name)
      if (!child) {
        child = { name, label: name, path: fullPath, isDir: false, children: [] }
        node.children.push(child)
      }
      if (isLast) {
        child.isDir = isDir
        child.path = isDir ? fullPath + '/' : fullPath
      } else {
        child.isDir = true
        child.path = fullPath + '/'
      }
      node = child
    }
  }
  sortTree(root.children)
  return root.children
}

function sortTree(nodes) {
  nodes.sort((a, b) => (a.isDir === b.isDir ? a.label.localeCompare(b.label) : a.isDir ? -1 : 1))
  for (const n of nodes) {
    if (n.children && n.children.length) sortTree(n.children)
  }
}

async function onImportTreeNodeClick(data) {
  if (data.isDir) return
  importForm.selectedPath = data.path
  importForm.previewContent = ''
  importPreviewLoading.value = true
  try {
    const { content } = await getPackageFile(importForm.versionId, data.path)
    importForm.previewContent = content || ''
  } catch (err) {
    // 404 / 非文本等：提示但不阻断，用户仍可手动粘贴
    toastError(err)
    importForm.previewContent = '（读取失败，可手动粘贴内容后保存）'
  } finally {
    importPreviewLoading.value = false
  }
}

async function onImportConfirm() {
  if (!importForm.selectedPath) return
  const fileKey = importForm.selectedPath
  const content = importForm.previewContent
  if (!content || content.startsWith('（')) {
    ElMessage.warning('该文件内容不可用，请手动编辑')
    return
  }
  // 直接以路径型 fileKey 落库（upsert 语义：同路径重复导入覆盖）
  configSaving.value = true
  try {
    await saveConfigFile(currentProject.value.id, { fileKey, content }, currentServiceId.value)
    ElMessage.success(`已导入 ${fileKey} 为配置覆盖项`)
    importDialog.value = false
    await loadConfigFiles()
  } catch (err) {
    toastError(err)
  } finally {
    configSaving.value = false
  }
}

function openProjectDialog(row) {
  Object.assign(projectForm, row ? {
    id: row.id, name: row.name, description: row.description, multiService: row.multiService,
    defaultStartCmd: row.defaultStartCmd, defaultPort: row.defaultPort, defaultHealthPath: row.defaultHealthPath,
  } : emptyProject())
  projectDialog.value = true
}

async function onSaveProject() {
  if (!projectForm.name) {
    ElMessage.warning('项目名必填')
    return
  }
  projectSaving.value = true
  try {
    const payload = {
      description: projectForm.description || undefined,
      detectedType: undefined,
      defaultStartCmd: projectForm.defaultStartCmd || undefined,
      defaultPort: projectForm.defaultPort || undefined,
      defaultHealthPath: projectForm.defaultHealthPath || undefined,
    }
    if (projectForm.id) {
      await updateProject(projectForm.id, payload)
    } else {
      await createProject({
        name: projectForm.name,
        multiService: projectForm.multiService,
        ...payload,
      })
    }
    ElMessage.success('保存成功')
    projectDialog.value = false
    await loadProjects()
    if (currentProject.value) await loadVersions()
  } catch (err) {
    toastError(err)
  } finally {
    projectSaving.value = false
  }
}

function openUpload() {
  if (!currentServiceId.value) {
    ElMessage.warning('请先创建服务（多服务项目需手动创建服务后再上传部署包）')
    return
  }
  versionNote.value = ''
  pickedFile.value = null
  uploadMode.value = 'file'
  Object.assign(gitForm, { gitUrl: '', gitRef: '', gitUsername: '', gitPassword: '' })
  resetChunkProgress()
  uploadDialog.value = true
}

function resetChunkProgress() {
  uploadChunkProgress.uploaded = 0
  uploadChunkProgress.total = 0
  uploadChunkResumed.value = 0
}

function onFileChange(file) {
  pickedFile.value = file
}

async function onUpload() {
  const raw = pickedFile.value?.raw
  if (uploadMode.value === 'file') {
    if (!raw) {
      ElMessage.warning('请先选择文件')
      return
    }
  } else if (!gitForm.gitUrl.trim()) {
    ElMessage.warning('请填写 git 地址')
    return
  }
  uploading.value = true
  try {
    if (uploadMode.value === 'file' && raw.size > CHUNK_THRESHOLD) {
      // 大文件走分片上传（断点续传：同一文件重开对话框可复用未过期会话跳过已传片）
      await uploadChunked(raw)
    } else {
      const formData = new FormData()
      if (uploadMode.value === 'file') {
        formData.append('file', raw)
      } else {
        formData.append('gitUrl', gitForm.gitUrl.trim())
        if (gitForm.gitRef.trim()) formData.append('gitRef', gitForm.gitRef.trim())
        if (gitForm.gitUsername.trim()) formData.append('gitUsername', gitForm.gitUsername.trim())
        if (gitForm.gitPassword) formData.append('gitPassword', gitForm.gitPassword)
      }
      if (versionNote.value) formData.append('versionNote', versionNote.value)
      if (currentServiceId.value) formData.append('serviceId', currentServiceId.value)
      await uploadPackage(formData)
    }
    ElMessage.success(uploadMode.value === 'git' ? 'git 拉取成功，版本已创建' : '上传成功，类型识别完成')
    uploadDialog.value = false
    // 包内可能自带 compose/env/nginx 配置：上传后自动入库，刷新配置文件列表展示
    await Promise.all([loadProjects(), loadVersions(), loadConfigFiles()])
  } catch (err) {
    toastError(err)
  } finally {
    uploading.value = false
  }
}

async function uploadChunked(raw) {
  const pid = currentProject.value.id
  const key = `${raw.name}|${raw.size}|${CHUNK_SIZE}|${currentServiceId.value}`
  const total = Math.ceil(raw.size / CHUNK_SIZE)

  // 断点续传：复用同一文件（名称+大小+分片大小）未完成的会话
  if (activeSession && activeSession.key === key && activeSession.projectId === pid) {
    try {
      const indexes = (await getUploadProgress(pid, activeSession.sessionId)) || []
      if (indexes.length > 0) {
        uploadChunkProgress.total = total
        uploadChunkProgress.uploaded = indexes.length
        uploadChunkResumed.value = indexes.length
        await continueChunkUpload(pid, activeSession.sessionId, raw, total, new Set(indexes))
        return
      }
    } catch {
      // 会话已失效（FAILED/被清理）→ 下方重新初始化
    }
    activeSession = null
  }

  // 无可用会话 → 全新初始化（服务端校验大小/分片参数，分片数上限 5000）
  const { sessionId } = await initChunkUpload(pid, {
    fileName: raw.name,
    totalSize: raw.size,
    chunkSize: CHUNK_SIZE,
    serviceId: currentServiceId.value,
  })
  activeSession = { key, sessionId, projectId: pid, total }
  uploadChunkProgress.total = total
  uploadChunkProgress.uploaded = 0
  uploadChunkResumed.value = 0
  await continueChunkUpload(pid, sessionId, raw, total, new Set())
}

async function continueChunkUpload(pid, sessionId, raw, total, uploadedSet) {
  for (let i = 0; i < total; i++) {
    if (uploadedSet.has(i)) continue
    const end = Math.min((i + 1) * CHUNK_SIZE, raw.size)
    const blob = raw.slice(i * CHUNK_SIZE, end)
    await uploadChunk(pid, sessionId, i, blob, raw.name)
    uploadedSet.add(i)
    uploadChunkProgress.uploaded++
  }
  // 合并落库（服务端合并后实算 sha256，不匹配会拒绝）
  await completeChunkUpload(pid, {
    sessionId,
    versionNote: versionNote.value || undefined,
  })
  // 成功 → 清除会话记录，下次同文件按全新上传处理
  activeSession = null
}

async function uploadPackage(formData) {
  // 复用 http 实例以带上 Bearer；上传走 multipart（大文件不限时）
  await http.post(`/projects/${currentProject.value.id}/packages`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0,
  })
}

async function onDownload(row) {
  try {
    await downloadBlob(`/projects/packages/${row.id}/download`, row.fileName)
  } catch (err) {
    toastError(err)
  }
}

async function onDeleteVersion(row) {
  try {
    await ElMessageBox.confirm(`确定删除版本「${row.fileName}」？部署中引用的版本无法删除。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteVersion(row.id)
    ElMessage.success('已删除')
    await loadVersions()
  } catch (err) {
    toastError(err)
  }
}

async function onDeleteProject(row) {
  // 预查项目下所有服务器实例：存在 RUNNING 实例时弹窗提醒，禁止删除
  let instances = []
  try {
    instances = await listServiceInstances({ projectId: row.id })
  } catch (err) {
    toastError(err)
    return
  }
  const running = (instances || []).filter((i) => i.status === 'RUNNING')
  if (running.length) {
    const names = running.map((i) => i.name).join('、')
    try {
      await ElMessageBox.alert(
        `项目「${row.name}」有 ${running.length} 个服务正在运行（${names}），不能删除。请先停止这些服务后再删除项目。`,
        '项目删除被阻止',
        { type: 'warning', confirmButtonText: '知道了' },
      )
    } catch {
      // 用户关闭弹窗
    }
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除项目「${row.name}」？其全部版本与配置文件将被级联删除，此操作不可恢复。`,
      '删除项目',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteProject(row.id)
    ElMessage.success('项目已删除')
    if (currentProject.value?.id === row.id) {
      currentProject.value = null
      versions.value = []
      configFiles.value = []
    }
    await loadProjects()
    if (!currentProject.value && projects.value.length) {
      await selectProject(projects.value[0])
    }
  } catch (err) {
    toastError(err)
  }
}

function onDeploy(row) {
  router.push({ name: 'deployments', query: { project: currentProject.value?.id, version: row.id } })
}

onMounted(async () => {
  await loadProjects()
  if (projects.value.length) await selectProject(projects.value[0])
})
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.service-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.service-bar-label {
  font-size: 13px;
  color: #606266;
  margin-right: 4px;
}
.service-tabs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.service-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.config-card {
  margin-top: 12px;
}
.config-textarea :deep(textarea) {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 13px;
}
.cfg-key {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  color: #409eff;
}
.import-hint {
  font-size: 12px;
  color: #909399;
  margin-bottom: 10px;
  line-height: 1.6;
}
.import-node {
  font-size: 13px;
  user-select: none;
}
.import-node.is-dir {
  color: #606266;
  font-weight: 500;
}
.import-preview-card {
  margin-bottom: 8px;
}
.import-preview {
  max-height: 420px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
