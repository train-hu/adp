# 自动化部署平台（auto-deploy-platform）

AI 驱动的自动化部署平台：管理目标服务器与项目，通过 SSH 通道上传部署包（支持分片上传断点续传、git 地址直接打包），一键执行「构建 → 上传 → 启动 → 健康检查」部署流程，并提供服务实例管理、文档中心、LLM 模型配置、AI 自主部署与 AI 助手、用户与权限（RBAC）、Webhook 告警与监控指标等完整能力。

## 功能模块

| 模块 | 说明 |
| --- | --- |
| 服务器管理 | 目标服务器（IP/SSH 端口/凭据），凭据 AES 加密落库，连接可用性检查 |
| 数据库管理 | 多子服务与数据库连接管理 |
| 项目管理 | 项目（归属/成员）、部署包版本管理（上传、git clone 打包）、多部署模式 |
| 分片上传 | 大文件分片上传、断点续传、过期会话自动清理（单文件上限 2GB） |
| 部署任务 | 流水线式步骤执行（上传 → 启动 → 健康检查），SSE 流式日志，僵尸任务启动恢复，失败重试 |
| 服务实例 | 实例生命周期管理（启动/停止/重启）、在线状态探测 |
| 文档中心 | 部署文档、AI 决策记录、维护文档 |
| LLM 配置 | 模型接入配置（密钥加密存储） |
| AI 自主部署 | 基于 LLM 的自动部署决策与执行 |
| AI 助手 | ReAct 风格的对话助手（可调用平台工具） |
| 用户与权限 | 用户管理、RBAC（ADMIN/OPS 等角色）、JWT 认证、登录防爆破锁定 |
| 站内通知 | 部署事件通知与通知中心 |
| Webhook 告警 | 飞书/钉钉告警配置（ADMIN），部署失败等事件推送 |
| 监控指标 | 30 天部署统计/成功率/逐日趋势、JVM 与 HTTP 指标、实例健康一览（30s 自动刷新） |
| 审计日志 | 操作审计（默认保留 365 天） |

## 技术栈

**后端**

- Java 17 + Spring Boot 3.3.13
- PostgreSQL 15 + JPA（`ddl-auto: validate`）+ Flyway 数据库迁移
- Apache MINA SSHD 2.14.0（SSH 命令执行 + SFTP 传输，连接池 + 空闲回收）
- JGit 6.10（git 地址部署包：平台侧 clone → zip）
- spring-security-crypto（BCrypt 密码哈希）+ 自研 JWT 认证
- Actuator + Micrometer Prometheus（`/actuator/prometheus`）
- Testcontainers（PostgreSQL 全链路集成测试，`@Tag("integration")`，无 Docker 环境自动跳过）

**前端**

- Vue 3 + Vite（rolldown，要求 Node 20.19+ / 22.12+）
- Element Plus + vue-router + axios
- 开发环境 `/api` 走 Vite 代理到 8080；生产由 Nginx 同源反代

## 目录结构

```
├── src/main/java/com/ops/deploy/   # 后端源码（按模块分包）
│   ├── security/                   # 认证（JWT）、登录防爆破、RBAC 切面
│   ├── server/                     # 服务器管理
│   ├── project/                    # 项目/部署包/分片上传
│   ├── deploy/                     # 部署流水线、步骤执行器、服务实例
│   ├── ssh/                        # SSH/SFTP 执行器（Mina SSHD 连接池）
│   ├── database/                   # 数据库管理
│   ├── llm/  ai/  aiassistant/     # LLM 配置、AI 自主部署、AI 助手
│   ├── notify/                     # 站内通知 + Webhook 告警
│   ├── audit/                      # 审计日志
│   ├── monitor/                    # 监控指标聚合
│   ├── maintenance/                # 定时清理任务
│   └── user/                       # 用户管理
├── src/main/resources/
│   ├── application.yml             # 应用配置（全部支持环境变量覆盖）
│   └── db/migration/               # Flyway V1~V22 迁移脚本
├── src/test/java/                  # 单元测试 + Testcontainers 集成测试
├── web/                            # 前端（Vue3 + Vite）
│   └── src/views/                  # 13 个页面视图
└── deploy/
    └── prod/                       # 生产部署：docker-compose.yml、nginx.conf、部署脚本
```

## 构建

### 后端

```bash
# 运行全部测试（集成测试无 Docker 时自动跳过）
mvn test

# 打包（产出 target/auto-deploy-server-0.1.0-SNAPSHOT.jar）
mvn -DskipTests package
```

### 前端

```bash
cd web
npm ci
npm run dev      # 开发（5173，/api 代理到 8080）
npm run build    # 构建（产出 web/dist，需 Node 20.19+ / 22.12+）
```

## 本地运行

1. 准备 PostgreSQL，创建库 `autodeploy`
2. 启动后端（首次启动自动执行 Flyway 迁移，并种子化 admin 账号）：

```bash
java -jar target/auto-deploy-server-*.jar
```

3. 启动前端开发服务：`cd web && npm run dev`
4. 访问 http://localhost:5173 ，默认账号 `admin / admin123`（由 `DEPLOY_SEED_PASSWORD` 控制）

> 生产环境必须通过 `DEPLOY_JWT_SECRET` 配置强密钥（≥32 字节，非占位值），否则启动即 fail-fast 拒绝启动（`DEPLOY_JWT_SECRET_ENFORCE_STRONG=true`）。

## 主要配置项

均支持环境变量覆盖（见 [application.yml](file:///c:/code/auto-deploy-platform/src/main/resources/application.yml)）：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | localhost/5432/autodeploy/deploy/deploy123 | 数据库连接 |
| `DEPLOY_DATA_DIR` | `data` | 本地数据目录（上传分片、部署包） |
| `DEPLOY_MAX_UPLOAD_SIZE` | 2147483648 | 部署包上传上限（字节） |
| `CREDENTIAL_KEK` | dev 占位值 | 凭据 AES 加密主密钥（更换后旧凭据无法解密） |
| `DEPLOY_SEED_PASSWORD` | admin123 | 种子化 admin 初始密码 |
| `DEPLOY_JWT_SECRET` | dev 占位值 | JWT 签名密钥（生产必须覆盖） |
| `DEPLOY_JWT_EXPIRE_HOURS` | 24 | JWT 过期时间（小时） |
| `DEPLOY_LOGIN_MAX_FAILURES` / `DEPLOY_LOGIN_LOCK_DURATION` | 5 / 15m | 登录防爆破：失败次数阈值与锁定时长 |
| `DEPLOY_CLEANUP_RETENTION_CRON` | `0 30 3 * * *` | 数据保留清理定时任务 |
| `DEPLOY_SSH_CONNECTION_IDLE_TIMEOUT_MINUTES` | 30 | SSH 连接池空闲回收阈值 |

## 生产部署（Docker Compose）

目标机 `/opt/autodeploy` 目录，三容器栈（见 [docker-compose.yml](file:///c:/code/auto-deploy-platform/deploy/prod/docker-compose.yml)）：

| 容器 | 镜像 | 说明 |
| --- | --- | --- |
| `autodeploy-db` | postgres:15-alpine | 数据持久化于 `pgdata` volume |
| `autodeploy-backend` | eclipse-temurin:17-jre | 挂载 `./auto-deploy-server.jar`，数据目录 `deploy-data` volume |
| `autodeploy-web` | nginx:1.27-alpine | 托管 `./web-dist` 前端产物，同源反代 `/api`（含 SSE 流式），对外端口 **8081** |

```bash
# 全量部署（备份 + jar 原子切换 + 重建容器 + 健康检查）
bash deploy-on-252.sh

# 仅后端热更新（前端不变）
scp target/*.jar deploy@<host>:/opt/autodeploy/auto-deploy-server.jar.new
ssh deploy@<host> "bash /opt/autodeploy/deploy-backend-only.sh"

# 前端构建（252 上通过 docker 运行 node:22-alpine 构建，见 build-web-on-252.sh）
```

部署成功标志：`BACKEND_OK`（`/api/ping` 200）+ `WEB_HTTP=200`，三容器 Up。

监控端点（容器内）：`/actuator/prometheus`、`/actuator/health`。

## 测试

- 后端全量测试 **711+ 通过**：单元测试覆盖各业务模块，集成测试使用 Testcontainers 起真实 PostgreSQL 跑 Flyway 迁移与全链路
- 集成测试标注 `@Tag("integration")`，依赖 Docker；无 Docker 环境（如 Windows 本地）整类优雅跳过，不破坏默认 `mvn test` 基线

## 安全与稳定性

- **认证**：JWT（HMAC-SHA256），生产强制强密钥校验，fail-fast
- **登录防爆破**：同一 (IP, 用户名) 连续失败 5 次锁定 15 分钟
- **RBAC**：`@RequireRole` 切面 + 项目级可见性过滤（ADMIN 全量 / 非 ADMIN 按项目）
- **凭据加密**：SSH 凭据、LLM 密钥经 AES-GCM 加密落库（KEK 可配置）
- **审计**：敏感操作全量审计，默认保留 365 天
- **SSH 连接池**：按服务器维度缓存复用，空闲 30 分钟自动回收
- **数据保留**：定时清理过期分片会话（24h）、审计/通知/部署记录（按保留天数）
- **僵尸任务恢复**：启动时 PENDING 超时（10 分钟）未开始的部署任务自动恢复/标记
- **可观测**：Micrometer Prometheus 指标（JVM/HTTP/业务），Webhook 失败告警
