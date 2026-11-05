package com.ops.deploy.deploy;

import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.llm.LlmChatClient;
import com.ops.deploy.llm.LlmModelConfigEntity;
import com.ops.deploy.llm.LlmModelConfigRepository;
import com.ops.deploy.project.ConfigFileValidator;
import com.ops.deploy.project.DetectedResult;
import com.ops.deploy.project.PackageStorage;
import com.ops.deploy.project.PackageVersionEntity;
import com.ops.deploy.project.ProjectConfigFileRequest;
import com.ops.deploy.project.ProjectConfigFileService;
import com.ops.deploy.project.ProjectEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 项目配置文件自动生成服务：发起部署时若项目缺少必要配置（compose/nginx），
 * 按「AI 生成优先、规则模板兜底」策略自动补齐并 upsert 入库，无需手动配置。
 *
 * <p>策略：
 * <ol>
 *   <li>项目已有同 fileKey 配置 → 直接沿用（不覆盖，包内提取/用户编辑优先）；</li>
 *   <li>有启用的 LLM 模型 → 单轮 chat 依据项目配置 + 识别类型生成，产出须通过
 *       {@link ConfigFileValidator} 校验才采用；</li>
 *   <li>无模型 / AI 调用失败 / 产出校验不通过 → 规则模板兜底
 *       （compose 单 service 本地构建；nginx 复用 {@link NginxStartExecutor#buildDefaultNginxConf}），
 *       保证部署流程可继续。</li>
 * </ol>
 *
 * <p>compose 缺失且识别类型非 DOCKER（包内无 Dockerfile）时：AI 顺带生成一份 Dockerfile
 * （file_key=dockerfile）一并入库，compose 仍用本地构建（jar/war 构建产物为
 * {@code build.context: .}（workdir 根，app.jar 所在处）+ {@code dockerfile: extract/Dockerfile}，
 * 源码/归档包为 {@code build.context: ./extract}），部署时由 {@code DockerStartExecutor}
 * 把 Dockerfile 写入 {@code {workdir}/extract/Dockerfile}，开箱即用；无 LLM 时 Dockerfile
 * 用规则模板兜底（按包形态与识别类型选基础镜像与 COPY 方式）。
 */
@Service
public class ConfigFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileGenerator.class);

    private final ProjectConfigFileService configFileService;
    private final LlmModelConfigRepository llmModelRepository;
    private final LlmChatClient llmClient;
    private final CredentialCryptoService crypto;
    private final PackageStorage packageStorage;

    public ConfigFileGenerator(ProjectConfigFileService configFileService,
                               LlmModelConfigRepository llmModelRepository,
                               LlmChatClient llmClient,
                               CredentialCryptoService crypto,
                               PackageStorage packageStorage) {
        this.configFileService = configFileService;
        this.llmModelRepository = llmModelRepository;
        this.llmClient = llmClient;
        this.crypto = crypto;
        this.packageStorage = packageStorage;
    }

    /**
     * 确保项目具备 compose 配置（DOCKER 部署必需）。已存在则原样返回；缺失时
     * AI 生成 + 规则模板兜底后 upsert 入库。返回入库后的文件 key。
     *
     * <p>识别类型非 DOCKER（含 UNKNOWN/null）意味着包内无 Dockerfile：此时会先顺带生成一份
     * Dockerfile 入库（file_key=dockerfile，AI 优先、规则模板兜底），再生成 compose——部署时
     * Dockerfile 会被写入 {@code {workdir}/extract/Dockerfile}，保证本地构建有源可建。
     * 构建上下文按包形态选择：jar/war 构建产物安装后在 workdir 根（app.jar），上下文取
     * workdir 根（{@code context: .} + {@code dockerfile: extract/Dockerfile}）；
     * 源码/归档包内容在 extract 下，上下文取 {@code ./extract}。
     */
    @Transactional
    public String ensureCompose(ProjectEntity project, DetectedResult detected,
                                PackageVersionEntity version) {
        return ensureCompose(project, detected, version, null);
    }

    /**
     * 同 {@link #ensureCompose(ProjectEntity, DetectedResult, PackageVersionEntity)}，
     * 但允许调用方传入本次部署最终解析出的端口（请求 > 服务级 > 项目默认 > 识别值），
     * 优先于 {@code resolvePort} 兜底，保证 compose 模板/AI 生成的宿主机端口与部署端口一致。
     */
    @Transactional
    public String ensureCompose(ProjectEntity project, DetectedResult detected,
                                PackageVersionEntity version, Integer preferredPort) {
        Long serviceId = version.getServiceId() == null ? 0L : version.getServiceId();
        Long projectId = project.getId();
        var existing = configFileService.list(projectId, serviceId).stream()
                .filter(vo -> "compose".equals(vo.getFileKey()))
                .findFirst();
        if (existing.isPresent()) {
            return "compose";
        }
        boolean hasPackageDockerfile =
                detected != null && "DOCKER".equals(detected.getDetectedType());
        // jar/war 构建产物：安装后被 mv 到 workdir 根（非 extract），构建上下文须为根目录
        boolean jarArtifact = detected != null
                && ("JAR".equals(detected.getArtifactKind()) || "WAR".equals(detected.getArtifactKind()));
        Integer port = preferredPort != null ? preferredPort : resolvePort(project, detected, 8080);
        // 包文件树（best-effort）：注入 prompt 让 AI 依据真实入口文件推断启动命令，
        // 并供校验器检查 Dockerfile CMD/ENTRYPOINT 引用的文件是否存在于包内。
        List<String> tree = packageTree(version);
        // 包内无 Dockerfile：先补生成 Dockerfile 入库，再让 compose 基于构建源本地构建
        // （jar/war 产物上下文为 workdir 根，源码/归档上下文为 extract）。
        if (!hasPackageDockerfile) {
            String dockerfile = generateWithAi(project, detected, "dockerfile", port, tree);
            if (dockerfile == null) {
                dockerfile = ruleDockerfile(detected == null ? null : detected.getDetectedType(),
                        port, jarArtifact);
                log.info("AI generation unavailable, using rule template for dockerfile of project {}", projectId);
            }
            upsert(serviceId, "dockerfile", dockerfile);
            log.info("Auto-generated Dockerfile (file_key=dockerfile) for project {} (no Dockerfile in package)", projectId);
        }
        String content = generateWithAi(project, detected, "compose", port, tree);
        if (content == null) {
            content = ruleCompose(project, port, jarArtifact);
            log.info("AI generation unavailable, using rule template for compose of project {}", projectId);
        }
        upsert(serviceId, "compose", content);
        return "compose";
    }

    /** 确保子服务具备 nginx 配置（NGINX 模式部署用），策略同 {@link #ensureCompose}。 */
    @Transactional
    public String ensureNginx(ProjectEntity project, DetectedResult detected,
                              PackageVersionEntity version) {
        Long serviceId = version.getServiceId() == null ? 0L : version.getServiceId();
        Long projectId = project.getId();
        boolean exists = configFileService.list(projectId, serviceId).stream()
                .anyMatch(vo -> "nginx".equals(vo.getFileKey()));
        if (exists) {
            return "nginx";
        }
        Integer port = resolvePort(project, detected, 80);
        String content = generateWithAi(project, detected, "nginx", port, packageTree(version));
        if (content == null) {
            content = NginxStartExecutor.buildDefaultNginxConf(project.getName(), port);
            log.info("AI generation unavailable, using rule template for nginx of project {}", projectId);
        }
        upsert(serviceId, "nginx", content);
        return "nginx";
    }

    /**
     * AI 生成配置内容：取第一个启用的 LLM 模型单轮对话生成；
     * 无模型/调用异常/产出校验不通过均返回 null（由调用方规则模板兜底）。
     *
     * @param packageTree 包内文件相对路径清单（可为 null/空，表示包信息不可用）
     */
    private String generateWithAi(ProjectEntity project, DetectedResult detected,
                                  String fileKey, Integer port, List<String> packageTree) {
        LlmModelConfigEntity model = firstEnabledModel();
        if (model == null) {
            return null;
        }
        try {
            String apiKey = crypto.decrypt(model.getApiKeyCipher());
            String reply = llmClient.chat(model.getBaseUrl(), apiKey, model.getModel(),
                    buildPrompt(project, detected, fileKey, port, packageTree));
            String content = stripCodeFence(reply);
            if (content == null || content.isBlank()) {
                return null;
            }
            if (ConfigFileValidator.validate(fileKey, content, packageTree) != null) {
                log.warn("AI generated {} config failed validation, fallback to rule template (project {})",
                        fileKey, project.getId());
                return null;
            }
            log.info("AI generated {} config for project {} via model {}", fileKey, project.getId(), model.getName());
            return content;
        } catch (RuntimeException e) {
            log.warn("AI generation of {} config failed for project {}: {}",
                    fileKey, project.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 规则模板兜底：compose 单 service 本地构建。
     *
     * @param jarArtifact 包为 jar/war 构建产物时，构建上下文为 workdir 根（app.jar 所在处），
     *                    Dockerfile 仍写在 extract/ 下经 dockerfile 键引用；否则上下文为 ./extract
     */
    private static String ruleCompose(ProjectEntity project, Integer port, boolean jarArtifact) {
        int p = port != null && port > 0 ? port : 8080;
        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");
        sb.append("  ").append(safeServiceName(project.getName())).append(":\n");
        sb.append("    build:\n");
        if (jarArtifact) {
            sb.append("      context: .\n");
            sb.append("      dockerfile: extract/Dockerfile\n");
        } else {
            sb.append("      context: ./extract\n");
        }
        sb.append("    container_name: ").append(safeContainerName(project.getName())).append("\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    ports:\n");
        sb.append("      - \"").append(p).append(":").append(p).append("\"\n");
        return sb.toString();
    }

    /**
     * 规则模板兜底：Dockerfile（包内无 Dockerfile 且无 LLM 时生成）。按包形态与识别类型生成：
     * <ul>
     *   <li>jar/war 构建产物：安装后位于 {@code {workdir}/app.jar}（extract 不存在），
     *       compose 构建上下文为 workdir 根，故直接 {@code COPY app.jar app.jar}；</li>
     *   <li>源码/归档包：内容在 {@code {workdir}/extract}，整目录 {@code COPY . .}。</li>
     * </ul>
     * 基础镜像：JAVA → temurin 17；NODE → node:20-alpine；
     * PYTHON → python:3.12-slim；其余（含 STATIC_WEB/UNKNOWN）→ nginx:alpine 静态托管。
     */
    private static String ruleDockerfile(String detectedType, Integer port, boolean jarArtifact) {
        int p = port != null && port > 0 ? port : 8080;
        String type = detectedType == null ? "" : detectedType.toUpperCase();
        if (type.startsWith("JAVA")) {
            if (jarArtifact) {
                return "FROM eclipse-temurin:17-jre\n"
                        + "WORKDIR /app\n"
                        + "COPY app.jar app.jar\n"
                        + "EXPOSE " + p + "\n"
                        + "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n";
            }
            return "FROM eclipse-temurin:17-jre\n"
                    + "WORKDIR /app\n"
                    + "COPY . .\n"
                    + "EXPOSE " + p + "\n"
                    + "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n";
        }
        if (type.startsWith("NODE")) {
            return "FROM node:20-alpine\n"
                    + "WORKDIR /app\n"
                    + "COPY . .\n"
                    + "RUN npm install --omit=dev || true\n"
                    + "EXPOSE " + p + "\n"
                    + "CMD [\"npm\", \"start\"]\n";
        }
        if (type.startsWith("PYTHON")) {
            return "FROM python:3.12-slim\n"
                    + "WORKDIR /app\n"
                    + "COPY . .\n"
                    + "EXPOSE " + p + "\n"
                    + "CMD [\"python\", \"-m\", \"http.server\", \"" + p + "\"]\n";
        }
        return "FROM nginx:alpine\n"
                + "COPY . /usr/share/nginx/html\n"
                + "EXPOSE 80\n"
                + "CMD [\"nginx\", \"-g\", \"daemon off;\"]\n";
    }

    private static String buildPrompt(ProjectEntity project, DetectedResult detected,
                                      String fileKey, Integer port, List<String> packageTree) {
        String detectedType = detected != null ? detected.getDetectedType() : "UNKNOWN";
        boolean jarArtifact = detected != null
                && ("JAR".equals(detected.getArtifactKind()) || "WAR".equals(detected.getArtifactKind()));
        StringBuilder sb = new StringBuilder();
        sb.append("你是部署配置专家。请为以下项目生成一个可直接使用的配置文件，只输出配置内容本身，");
        sb.append("不要输出任何解释、注释性文字或代码块围栏（```）。\n\n");
        sb.append("项目名：").append(project.getName()).append("\n");
        sb.append("识别类型：").append(detectedType).append("\n");
        sb.append("默认端口：").append(port).append("\n");
        if (packageTree != null && !packageTree.isEmpty()) {
            sb.append("部署包内文件清单（相对解压根目录，截断后共 ").append(packageTree.size())
                    .append(" 个文件）：\n");
            for (String rel : packageTree) {
                sb.append("  ").append(rel).append('\n');
            }
            sb.append("\n");
        }
        if (fileKey.equals("compose")) {
            sb.append("要求：生成 docker-compose YAML，顶层 services 键下定义服务；");
            if (jarArtifact) {
                sb.append("部署包是 jar/war 构建产物，安装后位于工作目录根（app.jar），构建上下文为工作目录根：")
                        .append("使用 build.context: . 且 dockerfile: extract/Dockerfile（Dockerfile 会被平台写入 extract/ 下）；");
            } else {
                sb.append("使用 build.context: ./extract 本地构建（包内或已自动补齐 Dockerfile）；");
            }
            sb.append("端口映射 \"").append(port).append(":").append(port).append("\"，restart: unless-stopped。\n");
        } else if (fileKey.equals("dockerfile")) {
            if (jarArtifact) {
                sb.append("要求：生成一个 Dockerfile。注意：部署包是 jar/war 构建产物，安装后应用文件位于构建上下文根目录的 app.jar，")
                        .append("COPY 必须写 COPY app.jar app.jar（严禁 COPY ../app.jar 等引用上下文外路径，也严禁 COPY extract/ ...）；")
                        .append("按识别类型选择合适的基础镜像（Java 应用用 temurin JRE 镜像），EXPOSE 端口 ").append(port)
                        .append("，ENTRYPOINT 用 [\"java\", \"-jar\", \"app.jar\"]，不要使用 buildkit 高级特性。\n");
            } else {
                sb.append("要求：生成一个 Dockerfile。注意：该 Dockerfile 会被放入构建上下文（即部署包解压目录 extract）的根目录内，")
                        .append("应用源码/依赖文件直接位于当前构建上下文中；因此 COPY/ADD 的源路径必须相对于当前上下文，")
                        .append("整目录拷贝一律写 COPY . /app/ 这类形式，严禁出现 COPY extract/ ...（会解析为 extract/extract/ 导致构建失败）；")
                        .append("按识别类型选择合适的基础镜像，EXPOSE 端口 ").append(port)
                        .append("，不要使用 buildkit 高级特性。\n");
            }
            sb.append("启动命令（CMD/ENTRYPOINT）必须依据上面的文件清单推断真实入口，")
                    .append("引用的脚本/模块文件必须在清单中实际存在，严禁臆造不存在的入口文件：")
                    .append("PYTHON 的 Web 应用（FastAPI/Flask 等，清单中无 __main__ 入口脚本）必须用 uvicorn 启动，")
                    .append("如 CMD [\"uvicorn\", \"<真实模块路径:app>\", \"--host\", \"0.0.0.0\", \"--port\", \"").append(port)
                    .append("\"]（模块路径用点号，如 app/main.py 的 FastAPI 实例 app 写作 app.main:app，")
                    .append("若实例名不是 app 则按清单推断并可用 --factory 或模块变量名）；")
                    .append("普通脚本才用 [\"python\", \"<脚本相对路径>\"]；")
                    .append("JAVA 用 [\"java\", \"-jar\", \"<jar 相对路径>\"] 或指定 mainClass；")
                    .append("NODE 若清单含 package.json 用 [\"npm\", \"start\"]，否则用 [\"node\", \"<入口文件相对路径>\"]。\n");
        } else {
            sb.append("要求：生成 nginx 站点配置（单个 server 块），listen ").append(port)
                    .append("，root 指向 /opt/deploy/").append(project.getName()).append("/current")
                    .append("，含 index 与 location / 的 try_files。\n");
        }
        return sb.toString();
    }

    /**
     * 解析部署包（zip/tar.gz/tar/普通文件）的文件树：流式扫描包内文件/目录的相对路径
     * （正斜杠、去首部 {@code ./} 与单根目录前缀），限制深度 8 层、总数 2000 个。
     *
     * <p>best-effort：包不存在 / 格式不符 / 解析异常均返回空列表（prompt 中省略清单、
     * 校验器跳过入口存在性检查），不阻断配置生成。
     */
    public List<String> packageTree(PackageVersionEntity version) {
        if (version == null || version.getStoragePath() == null) {
            return Collections.emptyList();
        }
        try (InputStream in = packageStorage.open(version.getStoragePath())) {
            byte[] buf = in.readAllBytes();
            String name = version.getFileName() == null ? "" : version.getFileName().toLowerCase();
            if (name.endsWith(".zip")) {
                return listZipTree(buf);
            }
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                return listTarTree(gunzip(buf));
            }
            if (name.endsWith(".tar")) {
                return listTarTree(buf);
            }
            // 非归档（如 jar 直接上传）：视为单文件包
            List<String> single = new ArrayList<>();
            single.add(version.getFileName());
            return single;
        } catch (Exception e) {
            log.warn("parse package tree of version {} failed: {}", version.getId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** zip 文件树：逐 entry 扫描，收集相对路径（目录带 '/' 后缀），限制数量。 */
    static List<String> listZipTree(byte[] bytes) throws IOException {
        List<String> raw = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                raw.add(e.getName().replace('\\', '/') + (e.isDirectory() ? "/" : ""));
                if (raw.size() >= TREE_MAX_ENTRIES) {
                    break;
                }
            }
        }
        return normalizeTree(raw);
    }

    /**
     * 读取部署包内单个文件的内容（按相对解压根路径定位，与 {@link #packageTree} 口径一致：
     * 去首部 {@code ./}、剥离单根目录前缀）。用于前端"从部署包导入配置文件"：把包内
     * {@code conf/config.toml} 之类的内容读出来填入配置编辑框，作为该文件的环境覆盖基准。
     *
     * <p>best-effort：包不存在 / 非归档 / 找不到对应 entry / 超大小上限均返回 null，
     * 由上层转成业务错误，不抛运行时异常。
     */
    public String readPackageFile(PackageVersionEntity version, String relPath) {
        if (version == null || version.getStoragePath() == null
                || relPath == null || relPath.isEmpty() || relPath.contains("..")
                || relPath.startsWith("/")) {
            return null;
        }
        try (InputStream in = packageStorage.open(version.getStoragePath())) {
            byte[] buf = in.readAllBytes();
            String name = version.getFileName() == null ? "" : version.getFileName().toLowerCase();
            byte[] content;
            if (name.endsWith(".zip")) {
                content = readZipFile(buf, relPath);
            } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                content = readTarFile(gunzip(buf), relPath);
            } else if (name.endsWith(".tar")) {
                content = readTarFile(buf, relPath);
            } else {
                // 非归档（如 jar）：仅当 relPath 等于包自身文件名时视为单文件
                content = version.getFileName() != null && version.getFileName().equals(relPath)
                        ? buf : null;
            }
            if (content == null) {
                return null;
            }
            if (content.length > PACKAGE_FILE_READ_MAX) {
                log.warn("package file {} of version {} too large ({} bytes), skip read",
                        relPath, version.getId(), content.length);
                return null;
            }
            return new String(content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("read package file {} of version {} failed: {}", relPath, version.getId(), e.getMessage());
            return null;
        }
    }

    /** zip 单文件读取：逐 entry 定位，按"剥离单根前缀后的相对路径"匹配目标。 */
    private static byte[] readZipFile(byte[] bytes, String target) throws IOException {
        // 第一遍：收集所有 entry 名计算单根前缀（与 tree 口径一致）
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName().replace('\\', '/'));
            }
        }
        String root = singleRootPrefix(names);
        // 第二遍：按规整后相对路径匹配目标
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                if (target.equals(normalizeEntry(e.getName().replace('\\', '/'), root))) {
                    return zis.readAllBytes();
                }
            }
        }
        return null;
    }

    /** tar 单文件读取：512 字节块头解析 name + size，仅匹配常规文件（跳过目录/符号链接），按"剥离单根前缀后的相对路径"定位目标。 */
    private static byte[] readTarFile(byte[] bytes, String target) {
        // 第一遍：收集 entry 名计算单根前缀（含目录，与 tree 口径一致）
        List<String> names = new ArrayList<>();
        for (int off = 0; off + 512 <= bytes.length; off += 512) {
            String n = tarName(bytes, off);
            if (n.isEmpty()) {
                break;
            }
            names.add(n);
            int size = tarSize(bytes, off);
            off += ((size + 511) / 512) * 512;
        }
        String root = singleRootPrefix(names);
        // 第二遍：定位目标常规文件并读取数据区
        for (int off = 0; off + 512 <= bytes.length; off += 512) {
            String n = tarName(bytes, off);
            if (n.isEmpty()) {
                break;
            }
            boolean dir = bytes[off + 156] == '5';
            int size = tarSize(bytes, off);
            int dataOff = off + 512;
            if (!dir && target.equals(normalizeEntry(n, root)) && dataOff + size <= bytes.length) {
                byte[] out = new byte[size];
                System.arraycopy(bytes, dataOff, out, 0, size);
                return out;
            }
            off += ((size + 511) / 512) * 512;
        }
        return null;
    }

    private static String tarName(byte[] bytes, int off) {
        int end = 0;
        while (off + end < off + 100 && bytes[off + end] != 0) {
            end++;
        }
        return new String(bytes, off, end, StandardCharsets.UTF_8);
    }

    private static int tarSize(byte[] bytes, int off) {
        // size 字段：offset 124 起 12 字节，八进制（可能带尾空格/NUL）
        String s = new String(bytes, off + 124, 12, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** tar 文件树：512 字节块头解析 name（前 100 字节）+ typeflag（'5' 目录，其余文件）+ size，
     *  每个 entry 后按 size 跳过文件数据区（对齐 512 边界）再读下一头，避免把数据区误当头部。 */
    static List<String> listTarTree(byte[] bytes) {
        List<String> raw = new ArrayList<>();
        int off = 0;
        while (off + 512 <= bytes.length) {
            // 全零块 = 归档结束
            boolean allZero = true;
            for (int i = 0; i < 512; i++) {
                if (bytes[off + i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                break;
            }
            int nameEnd = 0;
            while (nameEnd < 100 && bytes[off + nameEnd] != 0) {
                nameEnd++;
            }
            if (nameEnd == 0) {
                break;
            }
            String name = new String(bytes, off, nameEnd, StandardCharsets.UTF_8);
            boolean dir = bytes[off + 156] == '5';
            int size = tarSize(bytes, off);
            raw.add(name.replace('\\', '/') + (dir ? "/" : ""));
            if (raw.size() >= TREE_MAX_ENTRIES) {
                break;
            }
            // 跳过 512 头 + 文件数据区（目录 size=0），对齐下一个 512 边界
            off += 512 + ((size + 511) / 512) * 512;
        }
        return normalizeTree(raw);
    }

    private static final int TREE_MAX_DEPTH = 8;
    private static final int TREE_MAX_ENTRIES = 2000;
    /** 包内单文件读取上限（字节）：超过视为不适合前端编辑的配置文件（如二进制/大文件），读时跳过。 */
    private static final int PACKAGE_FILE_READ_MAX = 1024 * 1024;

    /**
     * 规整原始 entry 列表：去首部 {@code ./}、过滤绝对路径与 {@code ..}、深度超限剔除；
     * 若所有文件条目共享同一单根目录前缀（zip/tar 常套外层目录，如 {@code app-main/}）则统一剥离，
     * 保证清单相对解压根目录、可直接用于入口文件存在性判断。
     */
    static List<String> normalizeTree(List<String> raw) {
        Map<String, Boolean> kept = new java.util.LinkedHashMap<>();
        for (String n : raw) {
            String m = n;
            while (m.startsWith("./")) {
                m = m.substring(2);
            }
            if (m.isEmpty() || m.startsWith("/") || m.contains("..")) {
                continue;
            }
            String[] segs = m.split("/");
            if (segs.length > TREE_MAX_DEPTH) {
                continue;
            }
            kept.putIfAbsent(m, m.endsWith("/"));
        }
        String root = singleRootPrefix(kept.keySet());
        List<String> out = new ArrayList<>(kept.size());
        for (Map.Entry<String, Boolean> e : kept.entrySet()) {
            String k = e.getKey();
            if (root != null && k.startsWith(root)) {
                k = k.substring(root.length());
            }
            if (!k.isEmpty()) {
                out.add(e.getValue() ? k.endsWith("/") ? k : k + "/" : k);
            }
            if (out.size() >= TREE_MAX_ENTRIES) {
                break;
            }
        }
        return out;
    }

    /**
     * 计算单根目录前缀（zip/tar 外层包装目录，如 {@code app-main/}）：当存在多个条目且所有
     * 条目都落在同一个顶层目录下时返回该前缀（带尾 {@code /}），否则返回 null。
     * 供 {@link #normalizeTree} 与包内文件读取（{@link #readPackageFile}）共用，保证
     * "前端树里看到的相对路径"与"读取时定位到的 entry"口径一致。
     */
    static String singleRootPrefix(java.util.Collection<String> keys) {
        if (keys.size() <= 1) {
            return null;
        }
        String candidateRoot = keys.stream()
                .filter(k -> !k.endsWith("/"))
                .map(k -> k.substring(0, k.indexOf('/') < 0 ? k.length() : k.indexOf('/') + 1))
                .distinct()
                .filter(s -> s.endsWith("/"))
                .findFirst()
                .orElse(null);
        // 顶层不唯一（存在不在此根下的条目）则不剥离
        return (candidateRoot != null
                && keys.stream().noneMatch(k -> !k.startsWith(candidateRoot)))
                ? candidateRoot : null;
    }

    /** 把原始 entry 名规整为相对解压根路径（去首部 {@code ./}，剥离单根前缀）。与 {@link #normalizeTree} 口径一致。 */
    static String normalizeEntry(String raw, String root) {
        String m = raw;
        while (m.startsWith("./")) {
            m = m.substring(2);
        }
        if (root != null && m.startsWith(root)) {
            m = m.substring(root.length());
        }
        return m;
    }

    /** gzip 解压（tar.gz 用）。 */
    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return g.readAllBytes();
        }
    }

    private static String safeServiceName(String projectName) {
        String n = projectName == null ? "app" : projectName.toLowerCase();
        return n.replaceAll("[^a-z0-9-]", "-");
    }

    private static String safeContainerName(String projectName) {
        String n = projectName == null ? "app" : projectName.toLowerCase();
        return "deploy-" + n.replaceAll("[^a-z0-9-]", "-");
    }

    /** 剥离 AI 回复中可能的 ```yaml ... ``` 围栏（整段围栏或裸内容均兼容）。 */
    static String stripCodeFence(String reply) {
        if (reply == null) {
            return null;
        }
        String s = reply.trim();
        int start = s.indexOf("```");
        if (start >= 0) {
            int nl = s.indexOf('\n', start);
            int end = s.lastIndexOf("```");
            if (nl >= 0 && end > nl) {
                return s.substring(nl + 1, end).trim();
            }
        }
        return s;
    }

    /** 端口取值：项目默认端口 → 识别建议端口 → 兜底缺省值。 */
    private static Integer resolvePort(ProjectEntity project, DetectedResult detected, int fallback) {
        if (project != null && project.getDefaultPort() != null && project.getDefaultPort() > 0) {
            return project.getDefaultPort();
        }
        if (detected != null && detected.getSuggestedPort() != null && detected.getSuggestedPort() > 0) {
            return detected.getSuggestedPort();
        }
        return fallback;
    }

    private LlmModelConfigEntity firstEnabledModel() {
        return llmModelRepository.findAll().stream()
                .filter(LlmModelConfigEntity::isEnabled)
                .findFirst()
                .orElse(null);
    }

    private void upsert(Long serviceId, String fileKey, String content) {
        ProjectConfigFileRequest req = new ProjectConfigFileRequest();
        req.setFileKey(fileKey);
        req.setContent(content);
        configFileService.upsert(serviceId, req);
    }
}
