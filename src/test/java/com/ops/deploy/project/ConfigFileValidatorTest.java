package com.ops.deploy.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfigFileValidator 单测（纯静态方法，无 Spring）：
 * 覆盖 compose（合法 / 坏 YAML / 顶层非映射 / 缺 services / 空 services / services 非映射）、
 * env（合法 / export 前缀 / 注释空行跳过 / 坏行报行号）、nginx（配平 / 多余 / 缺右括号 / 缺 server）、
 * dockerfile（合法 COPY . / COPY extract/ 前缀拒绝 / 缺 FROM / 大小写与注释）、
 * 空内容、未知 fileKey 放行。
 */
class ConfigFileValidatorTest {

    // ---------- compose ----------

    @Test
    void compose_valid_shouldPass() {
        String content = "services:\n  app:\n    image: nginx\n";
        assertThat(ConfigFileValidator.validate("compose", content)).isNull();
    }

    @Test
    void compose_invalidYaml_shouldWarn() {
        String content = "services: [unclosed\n  - bad: : :\n";
        String warning = ConfigFileValidator.validate("compose", content);
        assertThat(warning).isNotNull();
        assertThat(warning).startsWith("YAML 解析失败");
    }

    @Test
    void compose_scalarTopLevel_shouldWarn() {
        String content = "just-a-string\n";
        assertThat(ConfigFileValidator.validate("compose", content)).contains("顶层必须是键值映射");
    }

    @Test
    void compose_missingServices_shouldWarn() {
        String content = "version: \"3\"\n";
        assertThat(ConfigFileValidator.validate("compose", content)).contains("services");
    }

    @Test
    void compose_emptyServices_shouldWarn() {
        String content = "services: {}\n";
        assertThat(ConfigFileValidator.validate("compose", content)).contains("services 为空");
    }

    @Test
    void compose_servicesNotMap_shouldWarn() {
        String content = "services:\n  - app\n";
        assertThat(ConfigFileValidator.validate("compose", content)).contains("services 必须是服务名→定义的映射");
    }

    // ---------- env ----------

    @Test
    void env_valid_shouldPass() {
        String content = "# comment\n\nDB_HOST=db\nexport API_KEY=abc123\nEMPTY=\n";
        assertThat(ConfigFileValidator.validate("env", content)).isNull();
    }

    @Test
    void env_badLine_shouldWarnWithLineNo() {
        String content = "OK=1\nbad line without equals\n";
        String warning = ConfigFileValidator.validate("env", content);
        assertThat(warning).contains("第 2 行");
        assertThat(warning).contains("KEY=VALUE");
    }

    @Test
    void env_badKeyShouldWarn() {
        String content = "1BAD=1\n";
        assertThat(ConfigFileValidator.validate("env", content)).contains("第 1 行");
    }

    // ---------- nginx ----------

    @Test
    void nginx_valid_shouldPass() {
        String content = "server {\n  listen 80;\n  location / {\n    try_files $uri =404;\n  }\n}\n";
        assertThat(ConfigFileValidator.validate("nginx", content)).isNull();
    }

    @Test
    void nginx_extraClosingBrace_shouldWarn() {
        String content = "server { listen 80; }\n}\n";
        assertThat(ConfigFileValidator.validate("nginx", content)).contains("多余的 '}'");
    }

    @Test
    void nginx_missingClosingBrace_shouldWarn() {
        String content = "server {\n  location / {\n  }\n";
        String warning = ConfigFileValidator.validate("nginx", content);
        assertThat(warning).contains("缺少 1 个 '}'");
    }

    @Test
    void nginx_noServerBlock_shouldWarn() {
        String content = "events {\n  worker_connections 1024;\n}\n";
        assertThat(ConfigFileValidator.validate("nginx", content)).contains("缺少 server 块");
    }

    // ---------- dockerfile ----------

    @Test
    void dockerfile_validCopyCurrentContext_shouldPass() {
        String content = "FROM python:3.11-slim\nWORKDIR /app\nCOPY . /app/\nEXPOSE 8080\nCMD [\"python\", \"main.py\"]\n";
        assertThat(ConfigFileValidator.validate("dockerfile", content)).isNull();
    }

    @Test
    void dockerfile_copyExtractPrefix_shouldWarn() {
        // 部署 15 实锤缺陷：AI 生成 COPY extract/ /app/，context 内不存在 extract/extract/
        String content = "FROM python:3.11-slim\nCOPY extract/ /app/\n";
        String warning = ConfigFileValidator.validate("dockerfile", content);
        assertThat(warning).contains("extract/");
        assertThat(warning).contains("COPY .");
    }

    @Test
    void dockerfile_addExtractDotPrefix_shouldWarn() {
        String content = "FROM node:20-alpine\nADD ./extract app\n";
        assertThat(ConfigFileValidator.validate("dockerfile", content)).contains("extract/");
    }

    @Test
    void dockerfile_missingFrom_shouldWarn() {
        String content = "WORKDIR /app\nCOPY . .\n";
        assertThat(ConfigFileValidator.validate("dockerfile", content)).contains("缺少 FROM");
    }

    @Test
    void dockerfile_caseInsensitiveAndComment_shouldHandle() {
        // 小写指令也能识别；注释行不计入
        String bad = "from nginx:alpine\n# COPY extract/ /x\n";
        assertThat(ConfigFileValidator.validate("dockerfile", bad)).isNull();
        String badCopy = "from nginx:alpine\ncopy extract . \n";
        assertThat(ConfigFileValidator.validate("dockerfile", badCopy)).contains("extract/");
    }

    // ---------- dockerfile 入口存在性校验（packageTree 上下文） ----------

    private static final java.util.List<String> PY_TREE = java.util.List.of(
            "app/", "app/main.py", "app/__init__.py", "requirements.txt");

    @Test
    void dockerfile_entryExistsInTree_shouldPass() {
        String content = "FROM python:3.11-slim\nCOPY . .\nCMD [\"uvicorn\", \"app.main:app\", \"--port\", \"8080\"]\n";
        assertThat(ConfigFileValidator.validate("dockerfile", content, PY_TREE)).isNull();
    }

    @Test
    void dockerfile_entryNotInTree_shouldWarn() {
        // AI 臆造 app.py（真实入口是 app/main.py）→ 拦截
        String content = "FROM python:3.11-slim\nCOPY . .\nCMD [\"python\", \"app.py\"]\n";
        String warning = ConfigFileValidator.validate("dockerfile", content, PY_TREE);
        assertThat(warning).isNotNull();
        assertThat(warning).contains("不存在于部署包内");
        assertThat(warning).contains("app.py");
    }

    @Test
    void dockerfile_shellFormEntryNotInTree_shouldWarn() {
        String content = "FROM python:3.11-slim\nCMD python missing.py --port 8080\n";
        String warning = ConfigFileValidator.validate("dockerfile", content, PY_TREE);
        assertThat(warning).isNotNull();
        assertThat(warning).contains("missing.py");
    }

    @Test
    void dockerfile_shellFormEntryExists_shouldPass() {
        String content = "FROM python:3.11-slim\nCMD python app/main.py\n";
        assertThat(ConfigFileValidator.validate("dockerfile", content, PY_TREE)).isNull();
    }

    @Test
    void dockerfile_moduleStyleToken_shouldPass() {
        // uvicorn 的 "app.main:app" 含冒号 → 非文件候选，跳过存在性检查
        String content = "FROM python:3.11-slim\nCMD uvicorn app.main:app --host 0.0.0.0 --port 8080\n";
        assertThat(ConfigFileValidator.validate("dockerfile", content, PY_TREE)).isNull();
    }

    @Test
    void dockerfile_treeNull_shouldSkipEntryCheck() {
        // 包信息不可用（tree=null）→ 跳过入口校验，不阻断
        String content = "FROM python:3.11-slim\nCMD [\"python\", \"app.py\"]\n";
        assertThat(ConfigFileValidator.validate("dockerfile", content, null)).isNull();
        // 空清单同样跳过
        assertThat(ConfigFileValidator.validate("dockerfile", content, java.util.List.of())).isNull();
    }

    // ---------- 边界 ----------

    @Test
    void emptyContent_shouldWarn() {
        assertThat(ConfigFileValidator.validate("compose", null)).isEqualTo("配置内容为空");
        assertThat(ConfigFileValidator.validate("env", "   \n")).isEqualTo("配置内容为空");
    }

    @Test
    void unknownFileKey_shouldPass() {
        assertThat(ConfigFileValidator.validate("other", "随便什么内容")).isNull();
        assertThat(ConfigFileValidator.validate(null, "随便什么内容")).isNull();
    }

    @Test
    void composeHasServices_shouldMirrorValidate() {
        assertThat(ConfigFileValidator.composeHasServices("services:\n  a: {}\n")).isTrue();
        assertThat(ConfigFileValidator.composeHasServices("no-services-here")).isFalse();
    }
}
