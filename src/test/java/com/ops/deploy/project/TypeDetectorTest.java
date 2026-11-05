package com.ops.deploy.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TypeDetector 单测（纯逻辑，无 Spring）：临时目录构造特征文件 + 内存构造 zip/tar.gz/jar。
 * 覆盖：各技术识别、优先级、Dockerfile 叠加、jar/war、静态站、UNKNOWN、
 * 扫描上限截断、滑轨炸弹（解压大小/文件数谎报）拒绝、路径穿越拒绝、损坏包不抛 5xx。
 */
class TypeDetectorTest {

    private final TypeDetector detector = new TypeDetector();

    @TempDir
    Path tempDir;

    // ---------- 目录扫描：各技术特征 ----------

    @Test
    void dir_withPomXml_shouldDetectJavaMaven() throws Exception {
        Path dir = tempDir.resolve("maven");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(r.getBuildTool()).isEqualTo("maven");
        assertThat(r.getConfidence()).isEqualTo(100);
        assertThat(r.getEvidence()).containsExactly("pom.xml");
        assertThat(r.getError()).isNull();
    }

    @Test
    void dir_withGradleKts_shouldDetectJavaGradle() throws Exception {
        Path dir = tempDir.resolve("gradle");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins {}");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("JAVA_GRADLE");
        assertThat(r.getBuildTool()).isEqualTo("gradle");
        assertThat(r.getConfidence()).isEqualTo(95);
        assertThat(r.getEvidence()).containsExactly("build.gradle.kts");
    }

    @Test
    void dir_withPackageJsonStartScript_shouldDetectNodeWithSuggestions() throws Exception {
        Path dir = tempDir.resolve("node");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"),
                "{\"main\":\"server.js\",\"scripts\":{\"start\":\"node server.js\",\"dev\":\"node server.js\"}}");
        Files.writeString(dir.resolve("server.js"), "console.log(1)");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("NODE");
        assertThat(r.getBuildTool()).isEqualTo("npm");
        assertThat(r.getEntryPoint()).isEqualTo("server.js");
        assertThat(r.getSuggestedStartCmd()).isEqualTo("npm start");
        assertThat(r.getConfidence()).isEqualTo(85);
        assertThat(r.getEvidence()).containsExactly("package.json");
    }

    @Test
    void dir_withPackageJsonPortInScript_shouldSuggestPort() throws Exception {
        Path dir = tempDir.resolve("node-port");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"),
                "{\"scripts\":{\"start\":\"node -r ts-node/register src/index.ts --port 8123\"}}");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("NODE");
        assertThat(r.getSuggestedPort()).isEqualTo(8123);
        assertThat(r.getSuggestedStartCmd()).isEqualTo("npm start");
    }

    @Test
    void dir_withPackageJsonWithoutStartScript_shouldDetectNodeNoCmd() throws Exception {
        Path dir = tempDir.resolve("node-nostart");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"),
                "{\"main\":\"server.js\",\"scripts\":{\"test\":\"mocha\"}}");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("NODE");
        assertThat(r.getBuildTool()).isEqualTo("npm");
        assertThat(r.getSuggestedStartCmd()).isNull();
        assertThat(r.getConfidence()).isEqualTo(75);
    }

    @Test
    void dir_withRequirementsTxt_shouldDetectPython() throws Exception {
        Path dir = tempDir.resolve("py");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("requirements.txt"), "flask==3.0");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
        assertThat(r.getBuildTool()).isEqualTo("none");
        assertThat(r.getConfidence()).isEqualTo(75);
        assertThat(r.getEvidence()).containsExactly("requirements.txt");
    }

    @Test
    void dir_withManagePy_shouldDetectPython() throws Exception {
        Path dir = tempDir.resolve("py-django");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manage.py"), "# django");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
        assertThat(r.getEvidence()).containsExactly("manage.py");
    }

    @Test
    void dir_withPyprojectOnly_shouldDetectPython() throws Exception {
        Path dir = tempDir.resolve("py-pyproject");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pyproject.toml"), "[project]");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
    }

    @Test
    void dir_pythonWithAppPy_shouldSuggestStartCmd() throws Exception {
        Path dir = tempDir.resolve("py-app");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("requirements.txt"), "flask==3.0");
        Files.writeString(dir.resolve("app.py"), "print('hi')");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
        assertThat(r.getSuggestedStartCmd()).isEqualTo("python3 app.py");
    }

    @Test
    void dir_pythonWithMainPy_shouldSuggestStartCmd() throws Exception {
        Path dir = tempDir.resolve("py-main");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("requirements.txt"), "flask==3.0");
        Files.writeString(dir.resolve("main.py"), "print('hi')");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
        assertThat(r.getSuggestedStartCmd()).isEqualTo("python3 main.py");
    }

    @Test
    void dir_pythonWithManagePy_shouldSuggestRunserver() throws Exception {
        Path dir = tempDir.resolve("py-django-cmd");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manage.py"), "# django");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
        assertThat(r.getSuggestedStartCmd()).isEqualTo("python3 manage.py runserver");
    }

    @Test
    void dir_pythonWithoutEntrypoint_shouldHaveNoSuggestedStartCmd() throws Exception {
        Path dir = tempDir.resolve("py-no-entry");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("requirements.txt"), "flask==3.0");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
        assertThat(r.getSuggestedStartCmd()).isNull();
    }

    // ---------- 静态站 ----------

    @Test
    void dir_pureStaticWithHtml_shouldDetectStaticWeb() throws Exception {
        Path dir = tempDir.resolve("static");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), "<h1>hi</h1>");
        Files.writeString(dir.resolve("app.js"), "console.log(1)");
        Files.writeString(dir.resolve("style.css"), "body{}");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("STATIC_WEB");
        assertThat(r.getBuildTool()).isEqualTo("none");
        assertThat(r.getEntryPoint()).isEqualTo("index.html");
        assertThat(r.getConfidence()).isEqualTo(80);
        assertThat(r.getSuggestedPort()).isEqualTo(80);
        assertThat(r.getEvidence()).containsExactlyInAnyOrder("index.html", "app.js", "style.css");
    }

    @Test
    void dir_staticWithoutHtml_shouldBeUnknown() throws Exception {
        Path dir = tempDir.resolve("static-no-html");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("app.js"), "console.log(1)");
        Files.writeString(dir.resolve("style.css"), "body{}");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
    }

    @Test
    void dir_staticWithDataFiles_shouldDetectStaticWeb() throws Exception {
        // 回归：真实站点常内嵌 CSV 数据 / 3D 模型 / PWA manifest 等非纯媒体资源，
        // 旧版 STATIC_EXTENSIONS 白名单过严导致 allStatic=false → 误判 UNKNOWN → 走 PROCESS 模式强求启动命令。
        Path dir = tempDir.resolve("static-data");
        Files.createDirectories(dir.resolve("models"));
        Files.writeString(dir.resolve("index.html"), "<h1>hi</h1>");
        Files.writeString(dir.resolve("app.js"), "console.log(1)");
        Files.writeString(dir.resolve("style.css"), "body{}");
        Files.writeString(dir.resolve("data.csv"), "a,b\n1,2");
        Files.writeString(dir.resolve("models/model.obj"), "v 0 0 0");
        Files.writeString(dir.resolve("models/model.mtl"), "newmtl m");
        Files.writeString(dir.resolve("manifest.webmanifest"), "{}");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("STATIC_WEB");
        assertThat(r.getEntryPoint()).isEqualTo("index.html");
        assertThat(r.getSuggestedPort()).isEqualTo(80);
    }

    @Test
    void dir_staticWithGzipPrecompressed_shouldDetectStaticWeb() throws Exception {
        // 回归：nginx gzip_static 预压缩资源（*.html.gz/*.css.gz 等）是静态站常见产物，
        // 不在 STATIC_EXTENSIONS 白名单时 allStatic=false → 误判 UNKNOWN。
        Path dir = tempDir.resolve("static-gz");
        Files.createDirectories(dir.resolve("static/css"));
        Files.createDirectories(dir.resolve("html"));
        Files.writeString(dir.resolve("index.html"), "<h1>hi</h1>");
        Files.writeString(dir.resolve("index.html.gz"), "1f8b gzip");
        Files.writeString(dir.resolve("html/ie.html"), "<h1>ie</h1>");
        Files.writeString(dir.resolve("static/css/app.css"), "body{}");
        Files.writeString(dir.resolve("static/css/app.css.gz"), "1f8b gzip");
        Files.writeString(dir.resolve("favicon.ico"), "icon");
        Files.writeString(dir.resolve("robots.txt"), "User-agent: *");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("STATIC_WEB");
        assertThat(r.getEntryPoint()).isEqualTo("index.html");
        assertThat(r.getSuggestedPort()).isEqualTo(80);
    }

    // ---------- Dockerfile ----------

    @Test
    void dir_dockerfileAlone_shouldDetectDocker() throws Exception {
        Path dir = tempDir.resolve("docker");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Dockerfile"), "FROM openjdk:17");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("DOCKER");
        assertThat(r.getEvidence()).containsExactly("Dockerfile");
        assertThat(r.getConfidence()).isEqualTo(60);
    }

    @Test
    void dir_pomAndDockerfile_shouldBeJavaMavenWithDockerfileEvidence() throws Exception {
        Path dir = tempDir.resolve("maven-docker");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("Dockerfile"), "FROM openjdk:17");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(r.getEvidence()).containsExactlyInAnyOrder("pom.xml", "Dockerfile");
    }

    @Test
    void dir_nodeAndDockerfile_shouldBeNodeWithDockerfileEvidence() throws Exception {
        Path dir = tempDir.resolve("node-docker");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{}");
        Files.writeString(dir.resolve("Dockerfile"), "FROM node:20");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("NODE");
        assertThat(r.getEvidence()).containsExactlyInAnyOrder("package.json", "Dockerfile");
    }

    // ---------- 多特征优先级 ----------

    @Test
    void dir_nodeAndPython_shouldPreferNode() throws Exception {
        Path dir = tempDir.resolve("node-py");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{}");
        Files.writeString(dir.resolve("requirements.txt"), "requests");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("NODE");
        assertThat(r.getEvidence()).containsExactlyInAnyOrder("package.json", "requirements.txt");
    }

    @Test
    void dir_javaAndNode_shouldPreferJava() throws Exception {
        Path dir = tempDir.resolve("java-node");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("build.gradle"), "apply plugin: 'java'");
        Files.writeString(dir.resolve("package.json"), "{}");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("JAVA_GRADLE");
        assertThat(r.getEvidence()).containsExactlyInAnyOrder("build.gradle", "package.json");
    }

    @Test
    void dir_pythonAndDockerfile_shouldPreferPython() throws Exception {
        Path dir = tempDir.resolve("py-docker");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("requirements.txt"), "flask");
        Files.writeString(dir.resolve("Dockerfile"), "FROM python:3");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("PYTHON");
        assertThat(r.getEvidence()).containsExactlyInAnyOrder("requirements.txt", "Dockerfile");
    }

    // ---------- 无特征 / 空目录 ----------

    @Test
    void dir_withoutAnyFeature_shouldBeUnknown() throws Exception {
        Path dir = tempDir.resolve("nothing");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("readme.txt"), "hello");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getBuildTool()).isEqualTo("none");
        assertThat(r.getConfidence()).isEqualTo(5);
        assertThat(r.getEvidence()).isEmpty();
    }

    @Test
    void dir_empty_shouldBeUnknown() throws Exception {
        Path dir = tempDir.resolve("empty");
        Files.createDirectories(dir);

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
    }

    // ---------- 扫描深度与文件数上限 ----------

    @Test
    void dir_deepFilesBeyondTwoLevels_areNotScanned() throws Exception {
        Path dir = tempDir.resolve("deep");
        Path deep = dir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("pom.xml"), "<project/>");

        DetectedResult r = detector.detect(dir);

        // 深度限制（2 层）：第 3 层的 pom.xml 不应命中
        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
    }

    @Test
    void dir_fileCountLimit_shouldNotCrashAndStillDetect() throws Exception {
        Path dir = tempDir.resolve("many");
        Files.createDirectories(dir);
        for (int i = 0; i < 2100; i++) {
            Files.writeString(dir.resolve("f" + i + ".bin"), "x");
        }
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        DetectedResult r = detector.detect(dir);

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(r.getEvidence()).contains("pom.xml");
    }

    // ---------- jar / war ----------

    @Test
    void jar_withMainClass_shouldDetectJavaWithMainClass() throws Exception {
        Path jar = tempDir.resolve("app.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("META-INF/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\nMain-Class: com.example.Main\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("com/example/Main.class"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }

        DetectedResult r = detector.detectArchive(jar, "jar");

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        // jar 为构建产物（免构建），buildTool=none，与 suggestedStartCmd（java -jar）语义一致
        assertThat(r.getBuildTool()).isEqualTo("none");
        // 产物形态：jar 部署后被 mv 到 workdir 根（非 extract），构建上下文须为根目录
        assertThat(r.getArtifactKind()).isEqualTo("JAR");
        assertThat(r.getEntryPoint()).isEqualTo("com.example.Main");
        assertThat(r.getSuggestedStartCmd()).isEqualTo("java -jar app.jar");
        assertThat(r.getConfidence()).isEqualTo(100);
        assertThat(r.getEvidence()).containsExactly("MANIFEST.MF:Main-Class=com.example.Main");
    }

    @Test
    void jar_withoutMainClass_shouldDetectJavaWithoutEntryPoint() throws Exception {
        Path jar = tempDir.resolve("lib.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("META-INF/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\n\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        DetectedResult r = detector.detectArchive(jar, "jar");

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(r.getBuildTool()).isEqualTo("none");
        assertThat(r.getArtifactKind()).isEqualTo("JAR");
        assertThat(r.getEntryPoint()).isNull();
        assertThat(r.getSuggestedStartCmd()).isNull();
        assertThat(r.getConfidence()).isEqualTo(80);
    }

    @Test
    void war_shouldDetectJavaWithContainerHint() throws Exception {
        Path war = tempDir.resolve("app.war");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(war))) {
            zos.putNextEntry(new ZipEntry("WEB-INF/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("WEB-INF/web.xml"));
            zos.write("<web-app/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        DetectedResult r = detector.detectArchive(war, "war");

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        // war 为构建产物（需容器部署），buildTool=none
        assertThat(r.getBuildTool()).isEqualTo("none");
        assertThat(r.getArtifactKind()).isEqualTo("WAR");
        assertThat(r.getSuggestedStartCmd()).contains("Tomcat");
        assertThat(r.getEvidence()).contains("WEB-INF/");
    }

    // ---------- zip / tar.gz 归档 ----------

    @Test
    void zip_withPomInside_shouldDetectJavaMaven() throws Exception {
        Path zip = tempDir.resolve("pkg.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeZipEntry(zos, "myapp/pom.xml", "<project/>");
            writeZipEntry(zos, "myapp/src.txt", "x");
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(r.getEvidence()).contains("pom.xml");
    }

    @Test
    void tarGz_withPomInside_shouldDetectJavaMaven() throws Exception {
        Path tgz = tempDir.resolve("pkg.tar.gz");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            writeTarEntry(gz, "myapp/pom.xml", "<project/>");
        }
        Files.write(tgz, bytes.toByteArray());

        DetectedResult r = detector.detectArchive(tgz, "tar.gz");

        assertThat(r.getDetectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(r.getEvidence()).contains("pom.xml");
    }

    @Test
    void zip_withComposeAndEnvInside_shouldExtractConfigs() throws Exception {
        Path zip = tempDir.resolve("cfg.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeZipEntry(zos, "myapp/pom.xml", "<project/>");
            writeZipEntry(zos, "myapp/docker-compose.yml", "services:\n  app:\n    image: nginx\n");
            writeZipEntry(zos, "myapp/.env", "DB_HOST=db\n");
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getExtractedConfigs().keySet()).containsExactlyInAnyOrder("compose", "env");
        assertThat(r.getExtractedConfigs().get("compose")).contains("services:");
        assertThat(r.getExtractedConfigs().get("env")).isEqualTo("DB_HOST=db\n");
        assertThat(r.getEvidence()).contains("docker-compose.yml", ".env");
    }

    @Test
    void zip_withConfigInNestedSubdir_shouldExtractFromDirectSubdir() throws Exception {
        Path zip = tempDir.resolve("cfgsub.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeZipEntry(zos, "myapp/README.md", "readme");
            writeZipEntry(zos, "myapp/config/nginx.conf", "server {\n  listen 80;\n}\n");
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getExtractedConfigs().keySet()).containsExactly("nginx");
        assertThat(r.getExtractedConfigs().get("nginx")).contains("server {");
        assertThat(r.getEvidence()).contains("config/nginx.conf");
    }

    @Test
    void zip_withoutConfigFiles_shouldHaveEmptyExtractedConfigs() throws Exception {
        Path zip = tempDir.resolve("nocc.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeZipEntry(zos, "myapp/pom.xml", "<project/>");
            writeZipEntry(zos, "myapp/app.jar", "jar-bytes");
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getExtractedConfigs()).isEmpty();
    }

    @Test
    void zip_rootConfigBeatsSubdirConfig_shouldPreferRoot() throws Exception {
        Path zip = tempDir.resolve("cfgboth.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeZipEntry(zos, "myapp/docker-compose.yml", "services:\n  root:\n    image: a\n");
            writeZipEntry(zos, "myapp/config/docker-compose.yml", "services:\n  sub:\n    image: b\n");
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getExtractedConfigs().get("compose")).contains("root");
        assertThat(r.getEvidence()).contains("docker-compose.yml");
    }

    @Test
    void unsupportedExtension_shouldBeUnknownWithError() {
        Path f = tempDir.resolve("notes.txt");
        try {
            Files.writeString(f, "hello");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        DetectedResult r = detector.detectArchive(f, "txt");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
    }

    @Test
    void plainHtml_shouldDetectStaticWeb() throws Exception {
        // 裸 HTML 单文件（非归档）直接判 STATIC_WEB，入口即该文件（部署时落盘为站点根 index.html）。
        Path f = tempDir.resolve("testindex.html");
        Files.writeString(f, "<h1>hello</h1>");

        DetectedResult r = detector.detectArchive(f, "html");

        assertThat(r.getDetectedType()).isEqualTo("STATIC_WEB");
        assertThat(r.getBuildTool()).isEqualTo("none");
        assertThat(r.getConfidence()).isEqualTo(70);
        assertThat(r.getSuggestedPort()).isEqualTo(80);
        assertThat(r.getEntryPoint()).isEqualTo("testindex.html");
        assertThat(r.getError()).isNull();
    }

    @Test
    void plainHtm_shouldDetectStaticWeb() throws Exception {
        Path f = tempDir.resolve("page.htm");
        Files.writeString(f, "<h1>hello</h1>");

        DetectedResult r = detector.detectArchive(f, "htm");

        assertThat(r.getDetectedType()).isEqualTo("STATIC_WEB");
        assertThat(r.getEntryPoint()).isEqualTo("page.htm");
    }

    // ---------- 异常输入：不抛 5xx，返回 UNKNOWN + error ----------

    @Test
    void corruptZip_shouldReturnUnknownWithNoThrow() throws Exception {
        Path zip = tempDir.resolve("bad.zip");
        Files.write(zip, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x01, 0x02, 0x03, 0x04});

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
    }

    @Test
    void corruptTarGz_shouldReturnUnknownWithNoThrow() throws Exception {
        Path tgz = tempDir.resolve("bad.tar.gz");
        Files.write(tgz, new byte[]{0x1F, (byte) 0x8B, 0x08, 0x00, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});

        DetectedResult r = detector.detectArchive(tgz, "tar.gz");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
    }

    @Test
    void zipBomb_exceedsDecompressedSize_shouldBeRejected() throws Exception {
        Path zip = tempDir.resolve("bomb.zip");
        // 高压缩比构造：513MB 全零（压缩后 on-disk zip 仅数百 KB 量级），
        // 解压时 totalBytes 触发 512MB 上限即拒绝，无需真实写 513MB 归档文件到磁盘。
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.setLevel(Deflater.BEST_SPEED);
            ZipEntry e = new ZipEntry("big.bin");
            zos.putNextEntry(e);
            byte[] block = new byte[1 << 20]; // 全零块，复用写入
            for (int i = 0; i < 513; i++) {
                zos.write(block);
            }
            zos.closeEntry();
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
        assertThat(r.getError()).contains("exceeds");
    }

    @Test
    void zipToomanyEntries_shouldBeRejected() throws Exception {
        Path zip = tempDir.resolve("many.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 100_010; i++) {
                ZipEntry e = new ZipEntry("e/" + i + ".bin");
                e.setSize(0);
                zos.putNextEntry(e);
                zos.closeEntry();
            }
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
    }

    @Test
    void tarGzToomanyEntries_shouldBeRejected() throws Exception {
        Path tgz = tempDir.resolve("many.tar.gz");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            for (int i = 0; i < 10_010; i++) {
                writeTarEntry(gz, "e/" + i + ".bin", "");
            }
        }
        Files.write(tgz, bytes.toByteArray());

        DetectedResult r = detector.detectArchive(tgz, "tar.gz");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
    }

    @Test
    void tar_withPathTraversalEntry_shouldBeRejected() throws Exception {
        Path tgz = tempDir.resolve("evil.tar.gz");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            writeTarEntry(gz, "../evil.txt", "malicious");
        }
        Files.write(tgz, bytes.toByteArray());

        DetectedResult r = detector.detectArchive(tgz, "tar.gz");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
        assertThat(tempDir.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void tarGz_declaredHugeSize_shouldBeRejectedQuickly() throws Exception {
        // 谎报 entry 声明 size≈1GB 但实际流极短（仅一个 512 字节头、无数据）：
        // 单 entry size 上限校验应快速拒绝，避免按 size 逐字节跳过/读取导致线程挂死。
        Path tgz = tempDir.resolve("hugesize.tar.gz");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            writeTarEntryWithDeclaredSize(gz, "big.bin", 1L << 30); // 声明 1GB
        }
        Files.write(tgz, bytes.toByteArray());

        long start = System.nanoTime();
        DetectedResult r = detector.detectArchive(tgz, "tar.gz");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
        assertThat(r.getError()).contains("exceeds");
        assertThat(elapsedMs).isLessThan(10_000); // 快速返回，不挂死
    }

    @Test
    void zip_withSymlinkEntry_shouldBeRejected() throws Exception {
        // 构造含 symlink 属性 entry 的 zip（external attributes 高位 = 0xA0000000 含 symlink 模式位），
        // 预扫描应拒绝（防"先写 link 文件、再写 link/../../pwn"的滑轨逃逸）。
        Path zip = tempDir.resolve("symlink.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry link = new ZipEntry("link");
            // 构造 Info-ZIP Unix extra field（tag=0x7855, size=9, mode=S_IFLNK=0xA000 小端）
            link.setExtra(new byte[] {
                    0x55, 0x78, // tag 0x7855
                    0x09, 0x00, // size 9
                    0x01,       // version
                    0x00, (byte) 0xA0, 0x00, 0x00, // mode 0xA000 (S_IFLNK) little-endian
                    0x00, 0x00, // uid
                    0x00, 0x00  // gid
            });
            zos.putNextEntry(link);
            zos.write("target".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            writeZipEntry(zos, "data.txt", "x");
        }

        DetectedResult r = detector.detectArchive(zip, "zip");

        assertThat(r.getDetectedType()).isEqualTo("UNKNOWN");
        assertThat(r.getError()).isNotNull();
        assertThat(r.getError()).contains("symlink entry not allowed");
    }

    // ---------- 测试辅助 ----------

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** 手工写 ustar 格式 tar entry（512 字节头 + 数据填充，不引入 commons-compress 测试依赖）。 */
    private static void writeTarEntry(GZIPOutputStream gz, String name, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        putOctal(header, 100, 0644, 8);
        putOctal(header, 124, 0, 8);
        putOctal(header, 124, 0, 8);
        putOctal(header, 124, data.length, 12);
        putOctal(header, 136, 0, 12);
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 5);
        header[263] = '0';
        long checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        byte[] sumBytes = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sumBytes, 0, header, 148, 6);
        header[154] = ' ';
        gz.write(header);
        if (data.length > 0) {
            gz.write(data);
            int pad = (512 - data.length % 512) % 512;
            for (int i = 0; i < pad; i++) {
                gz.write(0);
            }
        }
    }

    private static void putOctal(byte[] buf, int offset, long value, int len) {
        String s = String.format("%0" + (len - 1) + "o", value);
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, offset, b.length);
        buf[offset + len - 1] = 0;
    }

    /**
     * 写一个 ustar tar entry 头，但声明的 size 与真实数据不符（不写任何数据块），
     * 用于构造"谎报 TB 级 size + 短流"的攻击包，验证单 entry size 上限校验能快速拒绝。
     */
    private static void writeTarEntryWithDeclaredSize(GZIPOutputStream gz, String name, long declaredSize) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        putOctal(header, 100, 0644, 8);
        putOctal(header, 124, declaredSize, 12);
        putOctal(header, 136, 0, 12);
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 5);
        header[263] = '0';
        long checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        byte[] sumBytes = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sumBytes, 0, header, 148, 6);
        header[154] = ' ';
        gz.write(header);
        // 故意不写数据块：声明 size 巨大但流里没有，触发 size 上限快速拒绝
    }

    /** 压缩字节流（gzip 或 zip/deflate 原始流）；Deflater 内部会校验，用于构造合法归档。 */
    @SuppressWarnings("unused")
    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(out);
        dos.write(data);
        dos.close();
        return out.toByteArray();
    }

    @SuppressWarnings("unused")
    private static CRC32 crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc;
    }

    @SuppressWarnings("unused")
    private static InputStream streamOf(byte[] data) {
        return new java.io.ByteArrayInputStream(data);
    }
}
