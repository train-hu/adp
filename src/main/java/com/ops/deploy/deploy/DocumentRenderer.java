package com.ops.deploy.deploy;

import java.util.Map;

/**
 * 部署/维护文档渲染器（Task 8，纯函数，无外部依赖）。
 *
 * <p>M1 用内置 Markdown 模板 + 手写占位符替换：模板中 {@code {key}} 形式的占位符
 * 经变量表（{@link Map}）遍历替换，不引入任何模板引擎。按项目类型
 * （Java/Python/Node/STATIC/MIDDLEWARE）在 FAQ 章节做差异化填充
 * （{@link #faqForType}），启动命令等少量变量由 Service 按类型组装。
 *
 * <p>替换为多轮定界（最多 4 轮）：变量值中允许再嵌套占位符
 * （如 FAQ 文本内含 {port}/{unitName}），多轮替换直到不再变化。
 */
public final class DocumentRenderer {

    private static final String DEPLOY_TEMPLATE = """
            # 部署文档 — {projectName}

            > 由自动化部署平台生成，记录本次部署的关键信息、过程、产物与回滚步骤。

            ## 1. 基本信息

            | 项 | 值 |
            | --- | --- |
            | 项目 | {projectName} |
            | 版本 | {versionName} |
            | 项目类型 | {projectType} |
            | 部署方式 | {modeLabel} |
            | 目标服务器 | {serverName}（{serverHost}） |
            | 部署目录 | `{workdir}` |
            | 服务端口 | {port} |
            | 健康检查 | `{healthPath}` |
            | 操作人 | {operator} |
            | 开始时间 | {startedAt} |
            | 完成时间 | {finishedAt} |
            | 耗时 | {duration} |

            ## 2. 环境快照

            （部署时未采集环境快照）

            ## 3. 部署过程

            {timeline}

            ## 4. 产物与路径

            {deployArtifacts}

            ## 5. 回滚步骤

            在服务器 {serverName} 上可直接复制执行：

            ```bash
            {rollback}
            ```

            ## 6. AI 决策记录

            {aiDecision}
            """;

    private static final String MAINTAIN_TEMPLATE = """
            # 维护文档 — {projectName}

            > 由自动化部署平台生成。项目 {projectName} 的日常操作手册
            > （服务器：{serverName} / {serverHost}，部署方式：{modeLabel}）。

            ## 1. 日常操作手册

            以下命令可直接复制执行（在服务器 {serverName} 上）：

            | 操作 | 命令 |
            | --- | --- |
            | 启动服务 | `{startCommand}` |
            | 停止服务 | `{stopCommand}` |
            | 重启服务 | `{restartCommand}` |
            | 实时日志 | `{journalCommand}` |
            | 查看状态 | `{statusCommand}` |

            手动启动（不经过服务管理器，应急用）：

            ```bash
            {startupCommand}
            ```

            ## 2. 目录与配置说明

            {maintainArtifacts}

            ## 3. 常见问题（FAQ）

            {faq}

            ## 4. 排障指南

            1. **健康检查**：在服务器上执行
               `curl -sf -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:{port}{healthPath}`，
               返回 2xx 表示健康。
            2. **日志**：{logGuide}
            3. **常见错误**：
               - 端口被占用：`ss -lntp | grep {port}` 查看占用端口的进程。
               - 服务起不来：先看上方"实时日志"命令的最新输出（或查看 {workdir} 下的应用自身日志），定位异常堆栈。
               - 健康检查持续失败：手动 curl 健康端点，检查 HTTP 状态码与响应。
            """;

    private static final String JAVA_FAQ = """
            1. **服务启动失败 / 进程反复重启**：`journalctl -u {unitName} -n 200 --no-pager` \
            查看最近日志，常见为 Java 异常堆栈或内存不足。
            2. **Java 进程未正常退出，无法停止？**：`systemctl stop` 后用 \
            `ps -ef | grep java` 确认是否有残留进程，必要时 `kill <pid>` 手工处理。
            3. **端口被占用**：`ss -lntp | grep {port}` 查看哪个进程占用了端口。
            4. **内存溢出（OOM）**：检查启动参数中的堆设置，并检索日志中的 OOM / GC 信息。""";

    private static final String STATIC_FAQ = """
            1. **访问站点 404**：确认资源位于部署目录且路径正确；静态站点入口文件应为 `index.html`。
            2. **静态资源 403 / 目录列表被禁**：检查托管程序（nginx 等）的 root 目录与权限配置。
            3. **重新部署后静态资源未更新**：检查托管层缓存头；用 `ls -l` 确认部署目录内文件更新时间。
            4. **端口被占用**：`ss -lntp | grep {port}` 查看哪个进程占用了端口。""";

    private static final String NODE_FAQ = """
            1. **npm 依赖问题**：部署失败后确认部署目录内 `node_modules` 是否完整；`node -v` 查看版本。
            2. **端口被占用**：`ss -lntp | grep {port}` 查看哪个进程占用了端口。
            3. **启动脚本**：查看部署目录内 `package.json` 的 `scripts.start` 确认实际启动命令。""";

    private static final String PYTHON_FAQ = """
            1. **依赖问题**：检查部署目录内 `requirements.txt` 依赖是否已安装完整（pip list / 虚拟环境）。
            2. **端口被占用**：`ss -lntp | grep {port}` 查看哪个进程占用了端口。
            3. **Python 版本**：`python3 -V` 查看目标机版本，确认与项目要求一致。""";

    private static final String GENERIC_FAQ = """
            1. **服务起不来 / 看日志**：`journalctl -u {unitName} -n 200 --no-pager` 查看启动错误。
            2. **端口被占用**：`ss -lntp | grep {port}` 查看哪个进程占用了端口。
            3. **按项目实际情况排查**：参考项目自身文档与部署过程日志（部署文档 → 部署过程章节）。""";

    private static final String DOCKER_FAQ = """
            1. **容器反复重启 / 退出**：`docker ps -a` 查看容器状态与退出码，再用 \
            `docker-compose -f {workdir}/docker-compose.yml logs --tail 200 <服务名>` 查看容器日志。
            2. **端口被占用**：`ss -lntp | grep {port}` 查看哪个进程占用了宿主机端口；\
            容器端口映射在 compose 的 `ports` 字段，host 侧端口冲突时需调整。
            3. **镜像 / 构建失败**：`docker images` 确认镜像是否存在；本地构建时检查 `extract/Dockerfile` 是否完整。
            4. **容器与宿主机网络不通**：确认容器网络模式与端口映射（bridge 下需 `ports` 暴露）。""";

    private static final String NGINX_FAQ = """
            1. **访问 404 / 403**：确认站点根软链 `{symlinkPath}` 指向当前版本 `extract`；\
            检查站点配置 `{confPath}` 的 root 与权限。
            2. **改了文件没生效**：nginx 对静态站可能有缓存，用 `ls -l {symlinkPath}/` 确认实际指向版本。
            3. **配置错误导致 reload 失败**：`nginx -t` 校验配置语法，报错时先看配置再 reload。
            4. **端口被占用**：`ss -lntp | grep {port}` 查看哪个进程占用了端口。""";

    private DocumentRenderer() {
    }

    /** 渲染部署文档。 */
    public static String renderDeploy(Map<String, String> vars) {
        return fill(DEPLOY_TEMPLATE, vars);
    }

    /** 渲染维护文档。 */
    public static String renderMaintain(Map<String, String> vars) {
        return fill(MAINTAIN_TEMPLATE, vars);
    }

    /**
     * 按部署方式 + 项目类型选 FAQ。DOCKER/NGINX 为容器/静态托管方式，无 systemd unit，
     * 用各自专属 FAQ；其余（PROCESS）回退 {@link #faqForType}。
     */
    public static String faqForMode(String mode, String projectType) {
        String m = mode == null ? "" : mode.toUpperCase();
        return switch (m) {
            case "DOCKER" -> DOCKER_FAQ;
            case "NGINX" -> NGINX_FAQ;
            default -> faqForType(projectType);
        };
    }

    /**
     * 按项目类型选 FAQ（仅 PROCESS 方式使用；M1 差异化点之一）。
     * JAVA 系共用一份；STATIC/Node/Python 各一份；未知/缺失类型走通用兜底。
     */
    public static String faqForType(String projectType) {
        String t = projectType == null ? "" : projectType.toUpperCase();
        return switch (t) {
            case "JAVA_MAVEN", "JAVA_GRADLE" -> JAVA_FAQ;
            case "STATIC_WEB" -> STATIC_FAQ;
            case "NODE" -> NODE_FAQ;
            case "PYTHON" -> PYTHON_FAQ;
            default -> GENERIC_FAQ;
        };
    }

    /**
     * 占位符替换：遍历变量表，把模板中 {@code {key}} 替换为值。
     * 多轮（最多 4 轮）直到稳定，支持变量值内嵌套占位符（FAQ 内的 {port} 等）。
     */
    static String fill(String template, Map<String, String> vars) {
        String out = template;
        for (int i = 0; i < 4; i++) {
            String prev = out;
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
            if (out.equals(prev)) {
                break;
            }
        }
        return out;
    }
}
