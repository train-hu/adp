package com.ops.deploy.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 规则层部署包类型识别（纯逻辑，可单测，无外部依赖）。
 *
 * <p>输入：解压后的临时目录树（用 {@link java.nio.file} 扫描）+ 包文件扩展名。
 * 输出不可变值对象 {@link DetectedResult}。
 *
 * <p>识别规则（按优先级，确定性）：
 * <ol>
 *   <li>{@code pom.xml}（根目录优先）→ JAVA_MAVEN / maven</li>
 *   <li>{@code build.gradle}/{@code build.gradle.kts} → JAVA_GRADLE / gradle</li>
 *   <li>{@code package.json} → NODE / npm（有 main/scripts 提示时给建议）</li>
 *   <li>{@code requirements.txt}/{@code pyproject.toml}/{@code manage.py} → PYTHON</li>
 *   <li>纯静态资源（含至少一个 .html）→ STATIC_WEB，entryPoint=index.html（如有）</li>
 *   <li>根目录 {@code Dockerfile} → DOCKER（与其他特征叠加时记入 evidence，主类型以构建文件为准）</li>
 *   <li>都不命中 → UNKNOWN</li>
 * </ol>
 *
 * <p>多特征命中时优先级：JAVA 系 &gt; NODE &gt; PYTHON &gt; DOCKER &gt; STATIC。
 *
 * <p>jar 特殊处理：直接判 JAVA（detectedType=JAVA_MAVEN，buildTool=none——jar 为构建产物，
 * 本身免构建；有 Main-Class 则 entryPoint=主类、suggestedStartCmd={@code java -jar <file>}）；
 * war → JAVA 且 suggestedStartCmd 提示需容器。
 *
 * <p>解压：{@code .zip} 用 {@link ZipInputStream}，{@code .tar.gz} 用 {@link GZIPInputStream}
 * + 自实现最小 ustar 解析（不引入 commons-compress）。zip/tar 均做路径穿越校验与
 * 解压总字节/文件数上限（滑轨炸弹防护）。识别失败/无法解压不抛 5xx，返回 UNKNOWN + error。
 *
 * <p>扫描深度限制（2 层）与文件数上限（2000）防止恶意大包。
 */
@Component
public class TypeDetector {

    private static final Logger log = LoggerFactory.getLogger(TypeDetector.class);

    /** 扫描深度上限（相对解压根目录的最大层级）。 */
    private static final int MAX_SCAN_DEPTH = 2;
    /** 扫描文件数上限（防止恶意大包）。 */
    private static final int MAX_SCAN_FILES = 2000;
    /** 解压总字节数上限（滑轨炸弹防护），512MB。 */
    private static final long MAX_DECOMPRESSED_BYTES = 512L * 1024 * 1024;
    /** 解压文件数上限（滑轨炸弹防护）。 */
    private static final int MAX_EXTRACTED_FILES = 10_000;
    /** 静态资源扩展名集合。 */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".html", ".htm", ".js", ".css", ".png", ".jpg", ".jpeg",
            ".gif", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".eot", ".webp",
            // 静态站常见数据/辅助资源（站点常内嵌 CSV 数据、3D 模型、manifest 等，不应阻止 STATIC_WEB 判定）
            ".json", ".csv", ".txt", ".xml", ".webmanifest", ".map",
            // nginx gzip_static 预压缩资源（静态站常见 index.html.gz/app.css.gz 等）
            ".gz",
            ".glb", ".gltf", ".obj", ".mtl", ".fbx", ".stl");

    private static final Pattern PORT_IN_SCRIPT = Pattern.compile(
            "--port[= ](\\d{2,5})|PORT[=: ]+(\\d{2,5})|listen\\s*\\(\\s*['\"]?(\\d{2,5})",
            Pattern.CASE_INSENSITIVE);

    // ---------- 对外 API ----------

    /** 识别一个已存在的目录树（调用方负责解压）。 */
    public DetectedResult detect(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return DetectedResult.unknown("invalid directory");
        }
        List<String> relPaths;
        try {
            relPaths = scanFiles(dir);
        } catch (IOException e) {
            log.warn("type detection scan failed: {}", e.getMessage());
            return DetectedResult.unknown("scan failed: " + e.getMessage());
        }
        return classifyFromFiles(dir, relPaths);
    }

    /**
     * 识别一个归档/可执行包文件：按扩展名分发。
     * zip/tar.gz 先解压到临时目录再扫描（识别后调用方删除临时目录）；
     * jar/war 直接分析 manifest/结构；裸 .html/.htm 直接判 STATIC_WEB（单文件静态站）；
     * 其他扩展名返回 UNKNOWN。
     */
    public DetectedResult detectArchive(Path file, String extension) {
        if (file == null || !Files.isRegularFile(file)) {
            return DetectedResult.unknown("invalid archive file");
        }
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        switch (ext) {
            case "zip":
                return detectZipped(file);
            case "tar.gz", "tgz":
                return detectTarGz(file);
            case "jar":
                return detectJar(file, true);
            case "war":
                return detectWar(file);
            case "html", "htm":
                return detectPlainHtml(file);
            default:
                return DetectedResult.unknown("unsupported archive extension: " + extension);
        }
    }

    /** 裸 HTML 单文件（非归档）：判 STATIC_WEB，入口即该文件（部署时落盘到站点根）。 */
    private DetectedResult detectPlainHtml(Path file) {
        DetectedResult r = new DetectedResult();
        r.setDetectedType("STATIC_WEB");
        r.setBuildTool("none");
        r.setConfidence(70);
        r.setSuggestedPort(80);
        String name = file.getFileName().toString();
        r.setEntryPoint(name);
        r.addEvidence(name + " (single html file)");
        return r;
    }

    /**
     * 解压 zip 到临时目录并识别，识别后自清理临时目录。
     *
     * <p>先用 {@link java.util.zip.ZipFile} 预校验 zip 结构（END 头/中心目录），
     * 损坏包直接返回 UNKNOWN + error（{@code ZipInputStream} 对缺 END 头的包不会抛错，
     * 而是返回 null，故必须预校验）。
     *
     * <p>同时预扫描 symlink entry（解析 entry 的 Info-ZIP Unix extra field {@code 0x7855}，
     * Unix mode 为 S_IFLNK {@code 0xA000} 即 symlink），命中即拒绝（防"先写 link、再写
     * link/../../pwn" 的滑轨逃逸）。
     */
    private DetectedResult detectZipped(Path file) {
        try (java.util.zip.ZipFile probe = new java.util.zip.ZipFile(file.toFile())) {
            // 结构校验 + Unix symlink entry 预扫描（Info-ZIP extra field 0x7855 mode=S_IFLNK）
            for (var en = probe.entries(); en.hasMoreElements(); ) {
                if (isUnixSymlink(en.nextElement())) {
                    return DetectedResult.unknown("symlink entry not allowed");
                }
            }
        } catch (IOException e) {
            return DetectedResult.unknown("corrupt zip: " + e.getMessage());
        }
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("type-detect-zip-");
        } catch (IOException e) {
            return DetectedResult.unknown("cannot create temp dir: " + e.getMessage());
        }
        try {
            extractZipTo(file, tmpDir);
            return detect(tmpDir);
        } catch (IOException e) {
            return DetectedResult.unknown("zip extraction failed: " + e.getMessage());
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    /**
     * 判断 zip entry 是否为 Unix 符号链接：解析 Info-ZIP extra field 中标记 {@code 0x7855}
     * 的 Unix extension 块（data 布局：version(1) mode(4, 小端) uid(2) gid(2)），
     * (mode & S_IFMT) == S_IFLNK({@code 0xA000}) 即为 symlink。
     */
    private static boolean isUnixSymlink(ZipEntry e) {
        byte[] extra = e.getExtra();
        if (extra == null) {
            return false;
        }
        int i = 0;
        while (i + 4 <= extra.length) {
            int tag = (extra[i] & 0xFF) | ((extra[i + 1] & 0xFF) << 8);
            int size = (extra[i + 2] & 0xFF) | ((extra[i + 3] & 0xFF) << 8);
            if (tag == 0x7855 && i + 4 + 9 <= extra.length) {
                int mode = (extra[i + 4 + 1] & 0xFF) | ((extra[i + 4 + 2] & 0xFF) << 8)
                        | ((extra[i + 4 + 3] & 0xFF) << 16) | ((extra[i + 4 + 4] & 0xFF) << 24);
                if ((mode & 0xF000) == 0xA000) {
                    return true;
                }
            }
            if (size < 0) {
                break;
            }
            i += 4 + size;
        }
        return false;
    }

    private DetectedResult detectTarGz(Path file) {
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("type-detect-targz-");
        } catch (IOException e) {
            return DetectedResult.unknown("cannot create temp dir: " + e.getMessage());
        }
        try (InputStream raw = Files.newInputStream(file);
             GZIPInputStream gz = new GZIPInputStream(raw)) {
            extractTarTo(gz, tmpDir);
            return detect(tmpDir);
        } catch (IOException e) {
            return DetectedResult.unknown("tar.gz extraction failed: " + e.getMessage());
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    /** jar 包：分析 manifest 的 Main-Class。 */
    private DetectedResult detectJar(Path file, boolean withStartCmd) {
        String fileName = file.getFileName().toString();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(file.toFile())) {
            ZipEntry manifest = zf.getEntry("META-INF/MANIFEST.MF");
            String mainClass = null;
            if (manifest != null) {
                String text = readEntry(zf, manifest);
                mainClass = parseMainClass(text);
            }

            DetectedResult r = new DetectedResult();
            r.setDetectedType("JAVA_MAVEN");
            // jar/war 是构建产物，本身免构建（java -jar / 容器部署），buildTool 语义与
            // suggestedStartCmd 保持一致为 "none"（M1 不新增类型枚举，detectedType 保持 JAVA_MAVEN）
            r.setBuildTool("none");
            // 产物形态：jar 安装后被 mv 到 workdir 根（非 extract），Docker 构建上下文须为根目录
            r.setArtifactKind("JAR");
            if (mainClass != null) {
                r.setEntryPoint(mainClass);
                r.setConfidence(100);
                r.addEvidence("MANIFEST.MF:Main-Class=" + mainClass);
                if (withStartCmd) {
                    r.setSuggestedStartCmd("java -jar " + fileName);
                }
            } else {
                r.setConfidence(80);
                r.addEvidence("MANIFEST.MF");
            }
            return r;
        } catch (IOException e) {
            log.warn("jar analysis failed: {}", e.getMessage());
            return DetectedResult.unknown("jar analysis failed: " + e.getMessage());
        }
    }

    /** war 包：判 JAVA，提示需容器（M1 只记 evidence，不强求）；war 为构建产物，buildTool=none。 */
    private DetectedResult detectWar(Path file) {
        String fileName = file.getFileName().toString();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(file.toFile())) {
            boolean hasWebInf = zf.getEntry("WEB-INF/") != null
                    || zf.getEntry("WEB-INF/web.xml") != null;

            DetectedResult r = new DetectedResult();
            r.setDetectedType("JAVA_MAVEN");
            r.setBuildTool("none");
            r.setArtifactKind("WAR");
            r.setConfidence(90);
            r.addEvidence("WEB-INF/");
            r.setSuggestedStartCmd("war: deploy to a servlet container (e.g. Tomcat)");
            if (!hasWebInf) {
                // 无 WEB-INF 也按 war 处理（可能是不规范的包），降低置信度
                r.setConfidence(70);
            }
            return r;
        } catch (IOException e) {
            log.warn("war analysis failed: {}", e.getMessage());
            return DetectedResult.unknown("war analysis failed: " + e.getMessage());
        }
    }

    // ---------- 目录扫描 ----------

    /**
     * 求"有效根目录"：若解压根目录本身没有任何常规文件、且仅含唯一子目录
     * （zip/tar 常见单根包装目录，如 {@code myapp/...}），则深入该子目录作为识别根，
     * 使 {@code myapp/pom.xml} 等被当作根目录特征命中。否则返回原目录。
     */
    private Path effectiveRoot(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            boolean hasRegularFile = children.anyMatch(Files::isRegularFile);
            if (hasRegularFile) {
                return root;
            }
        } catch (IOException e) {
            return root;
        }
        try (Stream<Path> children = Files.list(root)) {
            List<Path> dirs = children.filter(Files::isDirectory).toList();
            if (dirs.size() == 1) {
                return dirs.get(0);
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return root;
    }

    /** 扫描目录树，返回相对路径列表（最多 MAX_SCAN_DEPTH 层、MAX_SCAN_FILES 个文件）。 */
    private List<String> scanFiles(Path root) throws IOException {
        List<String> relPaths = new ArrayList<>();
        Path normalized = root.toAbsolutePath().normalize();

        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH + 1)) {
            stream.forEach(p -> {
                if (relPaths.size() >= MAX_SCAN_FILES) {
                    return;
                }
                if (!Files.isRegularFile(p)) {
                    return;
                }
                String rel = normalized.relativize(p.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                relPaths.add(rel);
            });
        }
        return relPaths;
    }

    // ---------- 分类 ----------

    private DetectedResult classifyFromFiles(Path dir, List<String> relPaths) {
        // 根目录关键特征用确定性文件检查（不受扫描截断影响）；
        // 解压根若为单根包装目录（仅含一个子目录、无文件），深入后视为有效根。
        Path root = effectiveRoot(dir);

        boolean rootPom = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradleKts = Files.isRegularFile(root.resolve("build.gradle.kts"));
        boolean gradle = Files.isRegularFile(root.resolve("build.gradle"));
        boolean packageJsonExists = Files.isRegularFile(root.resolve("package.json"));
        boolean hasRequirements = Files.isRegularFile(root.resolve("requirements.txt"));
        boolean hasPyproject = Files.isRegularFile(root.resolve("pyproject.toml"));
        boolean hasManagePy = Files.isRegularFile(root.resolve("manage.py"));
        boolean hasDockerfile = Files.isRegularFile(root.resolve("Dockerfile"));

        // 设计文档为纯 html/js/css 即可判静态站，M1 收紧为必须含 .html，纯脚本包走 UNKNOWN 人工兜底
        boolean hasHtml = relPaths.stream().anyMatch(this::isHtmlFile);
        boolean allStatic = !relPaths.isEmpty() && relPaths.stream().allMatch(p ->
                isStaticResource(p) || isHiddenOrMetaFile(p));

        DetectedResult r = new DetectedResult();

        if (rootPom) {
            r.setDetectedType("JAVA_MAVEN");
            r.setBuildTool("maven");
            r.setConfidence(100);
            r.addEvidence("pom.xml");
        } else if (gradleKts || gradle) {
            r.setDetectedType("JAVA_GRADLE");
            r.setBuildTool("gradle");
            r.setConfidence(95);
            r.addEvidence(gradleKts ? "build.gradle.kts" : "build.gradle");
        } else if (packageJsonExists) {
            r.setDetectedType("NODE");
            r.setBuildTool("npm");
            applyNodeHints(r, root, "package.json");
        } else if (hasRequirements || hasPyproject || hasManagePy) {
            r.setDetectedType("PYTHON");
            r.setBuildTool("none");
            r.setConfidence(75);
            if (hasRequirements) {
                r.addEvidence("requirements.txt");
            }
            if (hasPyproject) {
                r.addEvidence("pyproject.toml");
            }
            if (hasManagePy) {
                r.addEvidence("manage.py");
            }
            applyPythonHints(r, root);
        } else if (hasDockerfile) {
            r.setDetectedType("DOCKER");
            r.setConfidence(60);
            r.setBuildTool("none");
            r.addEvidence("Dockerfile");
        } else if (hasHtml && allStatic) {
            r.setDetectedType("STATIC_WEB");
            r.setBuildTool("none");
            r.setConfidence(80);
            // 静态站走平台托管 nginx（监听 80），给出建议端口供部署表单预填
            r.setSuggestedPort(80);
            String index = relPaths.stream()
                    .filter(p -> p.equals("index.html") || p.endsWith("/index.html"))
                    .findFirst()
                    .orElse(null);
            if (index != null) {
                r.setEntryPoint(index);
            }
            for (String p : relPaths) {
                if (r.getEvidence().size() < 20) {
                    r.addEvidence(p);
                }
            }
        } else {
            r.setDetectedType("UNKNOWN");
            r.setBuildTool("none");
            r.setConfidence(5);
        }

        // 次要特征叠加：除主类型特征外，把同时存在的高优先级构建文件记入 evidence
        // （多特征并存时供前端展示与人工/AI 判断）
        if (rootPom) {
            if (gradle) {
                r.addEvidence("build.gradle");
            }
            if (gradleKts) {
                r.addEvidence("build.gradle.kts");
            }
            if (packageJsonExists) {
                r.addEvidence("package.json");
            }
        } else if (gradleKts || gradle) {
            if (packageJsonExists) {
                r.addEvidence("package.json");
            }
        } else if (packageJsonExists) {
            if (hasRequirements) {
                r.addEvidence("requirements.txt");
            }
            if (hasPyproject) {
                r.addEvidence("pyproject.toml");
            }
            if (hasManagePy) {
                r.addEvidence("manage.py");
            }
        }

        // Dockerfile 叠加：无论主类型，都把 Dockerfile 记入 evidence
        if (hasDockerfile && !r.getEvidence().contains("Dockerfile")) {
            r.addEvidence("Dockerfile");
        }

        // 包内配置文件提取（compose / env / nginx）：识别临时目录清理前读取全文，
        // 交由 ProjectService 入库项目配置文件列表（检查可用性后直接应用）。
        extractPackageConfigs(r, root);

        return r;
    }

    /** 单文件提取大小上限（与 project_config_file.content 1MB 上限对齐）。 */
    private static final long MAX_CONFIG_EXTRACT_BYTES = 1024L * 1024;

    /**
     * 包内配置文件候选（fileKey → 候选文件名按优先级降序，小写，大小写不敏感匹配）：
     * 根目录优先于直接子目录；同 fileKey 命中多个候选时取优先级最高者。
     */
    private static final List<Map.Entry<String, List<String>>> CONFIG_CANDIDATES = List.of(
            Map.entry("compose", List.of(
                    "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")),
            Map.entry("env", List.of(".env", ".env.production", ".env.prod")),
            Map.entry("nginx", List.of("nginx.conf")));

    /**
     * 从解压根目录提取部署包内自带的配置文件内容（compose / env / nginx）。
     *
     * <p>范围：有效根目录及其直接子目录（常见 compose/、config/ 包装结构）；
     * 提取失败（IO 等）仅告警跳过，不影响识别主流程。
     */
    private void extractPackageConfigs(DetectedResult r, Path root) {
        List<Path> rootFiles = listRegularFiles(root);
        List<Path> subFiles = listDirectSubdirFiles(root);

        for (Map.Entry<String, List<String>> candidate : CONFIG_CANDIDATES) {
            String fileKey = candidate.getKey();
            if (r.getExtractedConfigs().containsKey(fileKey)) {
                continue;
            }
            Path match = null;
            for (String fileName : candidate.getValue()) {
                match = findByFileName(rootFiles, fileName);
                if (match == null) {
                    match = findByFileName(subFiles, fileName);
                }
                if (match != null) {
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            try {
                if (Files.size(match) > MAX_CONFIG_EXTRACT_BYTES) {
                    log.warn("config file {} too large (>1MB), skipped", match.getFileName());
                    continue;
                }
                String content = Files.readString(match, StandardCharsets.UTF_8);
                r.getExtractedConfigs().put(fileKey, content);
                r.addEvidence(root.relativize(match).toString().replace('\\', '/'));
            } catch (IOException e) {
                log.warn("read config file {} failed: {}", match.getFileName(), e.getMessage());
            }
        }
    }

    /** 列出目录下的常规文件（目录不存在/IO 失败返回空列表）。 */
    private List<Path> listRegularFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("list dir {} failed: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** 列出目录直接子目录下的常规文件（一层，不递归）。 */
    private List<Path> listDirectSubdirFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path child : s.filter(Files::isDirectory).toList()) {
                try (Stream<Path> sub = Files.list(child)) {
                    sub.filter(Files::isRegularFile).forEach(result::add);
                } catch (IOException e) {
                    log.warn("list subdir {} failed: {}", child, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("list dir {} failed: {}", dir, e.getMessage());
        }
        return result;
    }

    /** 按文件名精确（大小写不敏感）匹配第一个命中文件。 */
    private Path findByFileName(List<Path> files, String name) {
        for (Path f : files) {
            if (f.getFileName().toString().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private boolean isHtmlFile(String p) {
        String lower = p.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private boolean isStaticResource(String p) {
        int dot = p.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return STATIC_EXTENSIONS.contains(p.substring(dot).toLowerCase(Locale.ROOT));
    }

    private boolean isHiddenOrMetaFile(String p) {
        // 允许常见元数据文件出现在静态站（.gitkeep、favicon 等已含）
        return p.startsWith(".") || p.equals("README.md") || p.equals("robots.txt")
                || p.equals("manifest.json");
    }

    /** 解析 package.json 给出 entryPoint / suggestedStartCmd / suggestedPort。 */
    private void applyNodeHints(DetectedResult r, Path dir, String packageJsonRel) {
        Path pkgPath = dir.resolve(packageJsonRel);
        String entryPoint = null;
        String startCmd = null;
        Integer port = null;

        try {
            String content = new String(Files.readAllBytes(pkgPath), StandardCharsets.UTF_8);
            Map<String, String> hints = parsePackageJsonHints(content);
            entryPoint = hints.get("main");
            String startScript = hints.get("startScript");
            if (startScript != null) {
                startCmd = "npm start";
            }
            port = extractPort(content);
        } catch (IOException e) {
            log.debug("read package.json failed: {}", e.getMessage());
        }

        r.addEvidence("package.json");
        if (entryPoint != null) {
            r.setEntryPoint(entryPoint);
        }
        if (startCmd != null) {
            r.setSuggestedStartCmd(startCmd);
        }
        if (port != null) {
            r.setSuggestedPort(port);
        }
        r.setConfidence((startCmd != null ? 85 : 75) + (port != null ? 5 : 0));
    }

    /**
     * 解析 Python 包给出建议启动命令：manage.py（Django）→ {@code python3 manage.py runserver}；
     * 其次 app.py / main.py / server.py / run.py → {@code python3 <file>}；皆无则不给建议
     * （suggestedStartCmd 保持 null，由项目默认值或用户手动填写兜底）。
     */
    private void applyPythonHints(DetectedResult r, Path root) {
        if (Files.isRegularFile(root.resolve("manage.py"))) {
            r.setSuggestedStartCmd("python3 manage.py runserver");
            return;
        }
        for (String entry : List.of("app.py", "main.py", "server.py", "run.py")) {
            if (Files.isRegularFile(root.resolve(entry))) {
                r.setSuggestedStartCmd("python3 " + entry);
                return;
            }
        }
    }

    /** 极简 JSON 提示解析（只取顶层 main / scripts.start，不引入 JSON 库依赖到核心识别）。 */
    private Map<String, String> parsePackageJsonHints(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        String main = extractJsonStringField(content, "main");
        if (main != null) {
            out.put("main", main);
        }
        // scripts.start：找到 "scripts": { ... "start": "..."
        String scriptsBlock = extractJsonObjectBlock(content, "scripts");
        if (scriptsBlock != null) {
            String start = extractJsonStringField(scriptsBlock, "start");
            if (start != null && !start.isBlank()) {
                out.put("startScript", start);
            }
        }
        return out;
    }

    private String extractJsonStringField(String content, String field) {
        String key = "\"" + field + "\"";
        int idx = content.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = content.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int open = content.indexOf('"', colon);
        if (open < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = open + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
                sb.append(c);
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String extractJsonObjectBlock(String content, String field) {
        String key = "\"" + field + "\"";
        int idx = content.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int brace = content.indexOf('{', idx);
        if (brace < 0) {
            return null;
        }
        int depth = 0;
        for (int i = brace; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(brace, i + 1);
                }
            }
        }
        return null;
    }

    private Integer extractPort(String content) {
        Matcher m = PORT_IN_SCRIPT.matcher(content);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    try {
                        int p = Integer.parseInt(m.group(g));
                        if (p >= 1 && p <= 65535) {
                            return p;
                        }
                    } catch (NumberFormatException ignored) {
                        // try next
                    }
                }
            }
        }
        return null;
    }

    // ---------- zip / tar 解压（含安全校验） ----------

    /**
     * 解压 zip 到目标目录。
     * 安全：路径穿越校验、解压总字节上限、文件数上限、zip 滑轨炸弹（entry size 谎报）。
     */
    private void extractZipTo(Path zipFile, Path targetDir) throws IOException {
        long totalBytes = 0;
        int fileCount = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (fileCount >= MAX_EXTRACTED_FILES) {
                    throw new IOException("too many files in archive (exceeds " + MAX_EXTRACTED_FILES + ")");
                }
                Path dest = safeResolve(targetDir, entry.getName());
                if (Files.exists(dest) && Files.isSymbolicLink(dest)) {
                    // 双保险：目标路径已是 symlink 则拒绝写入（防先写 link 再写 link/../../pwn 的滑轨逃逸）
                    throw new IOException("symlink entry not allowed");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    fileCount++;
                    Files.createDirectories(dest.getParent());
                    long written = 0;
                    try (OutputStream out = Files.newOutputStream(dest,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            written += n;
                            totalBytes += n;
                            if (totalBytes > MAX_DECOMPRESSED_BYTES) {
                                throw new IOException("decompressed size exceeds limit (" + MAX_DECOMPRESSED_BYTES + " bytes)");
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 从 ustar 流解压到目标目录（自实现最小 tar 解析，不引入 commons-compress）。
     * 安全：路径穿越校验、解压总字节上限、文件数上限。
     */
    private void extractTarTo(InputStream tarIn, Path targetDir) throws IOException {
        PushbackInputStream in = new PushbackInputStream(tarIn, 512);
        byte[] header = new byte[512];
        long totalBytes = 0;
        int fileCount = 0;

        while (true) {
            int read = readFully(in, header, 512);
            if (read < 512) {
                break;
            }
            // 全零头 → 归档结束
            boolean allZero = true;
            for (byte b : header) {
                if (b != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                break;
            }

            String name = readUstarString(header, 0, 100);
            long size = parseOctal(header, 124, 12);
            byte type = header[156];

            // 单 entry 声明 size 上限校验：谎报 TB 级 size 会挂死读线程（短流 + 按 size 跳过/读取），
            // 超过剩余可解压配额即按损坏包处理（detectTarGz 捕获 IOException 返回 UNKNOWN + error）
            if (size < 0 || size > MAX_DECOMPRESSED_BYTES - totalBytes) {
                throw new IOException("corrupt tar: declared size " + size
                        + " exceeds limit (" + MAX_DECOMPRESSED_BYTES + " bytes)");
            }

            // 路径穿越校验
            Path dest = safeResolve(targetDir, name);

            if (type == '5') {
                // 目录
                Files.createDirectories(dest);
            } else if (type == '0' || type == 0) {
                // 常规文件
                if (fileCount >= MAX_EXTRACTED_FILES) {
                    throw new IOException("too many files in archive (exceeds " + MAX_EXTRACTED_FILES + ")");
                }
                fileCount++;
                Files.createDirectories(dest.getParent());
                byte[] buf = new byte[8192];
                long remaining = size;
                try (OutputStream out = Files.newOutputStream(dest,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int n = in.read(buf, 0, toRead);
                        if (n < 0) {
                            break;
                        }
                        out.write(buf, 0, n);
                        remaining -= n;
                        totalBytes += n;
                        if (totalBytes > MAX_DECOMPRESSED_BYTES) {
                            throw new IOException("decompressed size exceeds limit (" + MAX_DECOMPRESSED_BYTES + " bytes)");
                        }
                    }
                }
            }

            // 跳过数据块的填充（到 512 字节边界）
            skipBlockPadding(in, size);
        }
    }

    private static void skipBlockPadding(PushbackInputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        // skip 在 GZIPInputStream 下按块跳过，避免逐字节 read()（谎报大 size 时 CPU 打满）；
        // 短流（EOF）时 skip 返回已跳过数（可能 < pad），best-effort 忽略（与既有行为一致）
        in.skip(pad);
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off;
    }

    private static String readUstarString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && end < buf.length && buf[end] != 0) {
            end++;
        }
        return new String(buf, off, end - off, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            char c = (char) (buf[off + i] & 0xFF);
            if (c == 0 || c == ' ') {
                break;
            }
            int d = c - '0';
            if (d < 0 || d > 7) {
                break;
            }
            v = v * 8 + d;
        }
        return v;
    }

    /** 安全解析归档内路径：拒绝绝对路径与 {@code ..} 段，归一化后必须仍在 targetDir 内。 */
    private Path safeResolve(Path targetDir, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty()) {
            throw new IOException("empty entry name");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("absolute path in archive: " + entryName);
        }
        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                throw new IOException("path traversal in archive: " + entryName);
            }
        }
        Path resolved = targetDir.resolve(normalized).toAbsolutePath().normalize();
        Path target = targetDir.toAbsolutePath().normalize();
        if (!resolved.startsWith(target)) {
            throw new IOException("path escapes archive target: " + entryName);
        }
        return resolved;
    }

    private static String readEntry(java.util.zip.ZipFile zf, ZipEntry entry) throws IOException {
        try (InputStream in = zf.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String parseMainClass(String manifest) {
        if (manifest == null) {
            return null;
        }
        for (String line : manifest.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("Main-Class:")) {
                String v = t.substring("Main-Class:".length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 递归删除目录（用于清理临时解压目录）。 */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            stream.forEach(paths::add);
            paths.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort 清理
                }
            }
        } catch (IOException e) {
            log.warn("failed to delete temp dir: {}", dir, e);
        }
    }
}
