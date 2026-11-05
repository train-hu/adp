package com.ops.deploy.deploy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentRenderer} 模板渲染单测（纯函数：变量 Map → markdown 字符串）。
 *
 * <p>覆盖：占位符替换（{projectName} 风格遍历 Map 替换）、按项目类型差异化
 * （Java/STATIC 的启动命令与 FAQ 内容差异）、未知类型走通用兜底、
 * 无旧版本的回滚步骤分支。
 */
class DocumentRendererTest {

    private static Map<String, String> baseVars() {
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("projectName", "demo");
        vars.put("versionName", "app.zip");
        vars.put("projectType", "JAVA_MAVEN");
        vars.put("modeLabel", "systemd 进程");
        vars.put("serverName", "web-01");
        vars.put("serverHost", "10.0.0.8");
        vars.put("sshUser", "deploy");
        vars.put("workdir", "/opt/deploy/demo/10");
        vars.put("port", "8080");
        vars.put("healthPath", "/health");
        vars.put("operator", "ops");
        vars.put("startedAt", "2026-10-14 10:00:00");
        vars.put("finishedAt", "2026-10-14 10:02:00");
        vars.put("duration", "2分0秒");
        vars.put("unitName", "deploy-demo.service");
        vars.put("startCmdRedacted", "java -jar app.jar");
        // 与 ServiceInstanceService 静态方法产出一致（保证维护文档命令与真实一致）
        vars.put("startCommand", "systemctl start deploy-demo.service");
        vars.put("stopCommand", "systemctl stop deploy-demo.service");
        vars.put("restartCommand", "systemctl restart deploy-demo.service");
        vars.put("journalCommand", "journalctl -u deploy-demo.service -f --no-pager");
        vars.put("statusCommand", "systemctl status deploy-demo.service");
        vars.put("deployArtifacts", "- 部署目录：`/opt/deploy/demo/10`\n- 解压产物：`/opt/deploy/demo/10/extract`\n- systemd unit：`/etc/systemd/system/deploy-demo.service`");
        vars.put("maintainArtifacts", "- 部署目录：`/opt/deploy/demo/10`（应用文件位于 `extract` 子目录）\n- 服务单元：`/etc/systemd/system/deploy-demo.service`");
        vars.put("logGuide", "用上方“实时日志”命令 `journalctl -u deploy-demo.service -f` 跟踪。");
        vars.put("aiDecision", "- **方案来源**：AI（LLM 生成）（方案 ID：3）");
        vars.put("rollback", "systemctl stop deploy-demo.service\nsystemctl daemon-reload && systemctl enable deploy-demo.service && systemctl restart deploy-demo.service");
        vars.put("timeline", "- **1. UPLOAD** — `SUCCESS`（10:00:00 ~ 10:00:05）\n  - 关键命令：`mkdir -p /opt/deploy/demo/10`");
        vars.put("faq", "FAQ-JAVA");
        vars.put("startupCommand", "cd /opt/deploy/demo/10 && java -jar app.jar");
        return vars;
    }

    @Test
    void renderDeploy_replacesAllPlaceholders() {
        String md = DocumentRenderer.renderDeploy(baseVars());
        assertThat(md)
                .contains("# 部署文档 — demo")
                .contains("| 项目 | demo |")
                .contains("| 部署方式 | systemd 进程 |")
                .contains("web-01")
                .contains("2分0秒")
                .contains("mkdir -p /opt/deploy/demo/10")
                .contains("方案来源")
                .contains("（部署时未采集环境快照）");
        // 占位符不应残留
        assertThat(md).doesNotContain("{projectName}").doesNotContain("{faq}").doesNotContain("{timeline}");
    }

    @Test
    void renderMaintain_replacesAllPlaceholders() {
        String md = DocumentRenderer.renderMaintain(baseVars());
        assertThat(md)
                .contains("# 维护文档 — demo")
                .contains("systemctl start deploy-demo.service")
                .contains("journalctl -u deploy-demo.service -f --no-pager")
                .contains("cd /opt/deploy/demo/10 && java -jar app.jar")
                .contains("FAQ-JAVA");
        assertThat(md).doesNotContain("{unitName}").doesNotContain("{startupCommand}");
    }

    @Test
    void faq_variesByProjectType_javaVsStatic() {
        Map<String, String> java = baseVars();
        java.put("projectType", "JAVA_MAVEN");
        java.put("faq", DocumentRenderer.faqForType("JAVA_MAVEN"));
        java.put("startupCommand", "cd /opt/deploy/demo/10 && java -jar app.jar");

        Map<String, String> staticSite = baseVars();
        staticSite.put("projectType", "STATIC_WEB");
        staticSite.put("faq", DocumentRenderer.faqForType("STATIC_WEB"));
        staticSite.put("startupCommand", "（未配置启动命令）");

        assertThat(DocumentRenderer.renderMaintain(java)).contains("Java 进程未正常退出");
        assertThat(DocumentRenderer.renderMaintain(staticSite)).contains("静态资源 403");
        assertThat(DocumentRenderer.renderMaintain(staticSite)).contains("（未配置启动命令）");
        // Java FAQ 不应出现在 STATIC 文档
        assertThat(DocumentRenderer.renderMaintain(staticSite)).doesNotContain("Java 进程未正常退出");
    }

    @Test
    void faq_unknownType_fallsBackToGeneric() {
        assertThat(DocumentRenderer.faqForType("UNKNOWN")).contains("按项目实际情况排查");
        assertThat(DocumentRenderer.faqForType(null)).contains("按项目实际情况排查");
    }

    @Test
    void rollback_noOldVersion_rendersStopAndDisable() {
        Map<String, String> vars = baseVars();
        vars.put("rollback", "systemctl stop deploy-demo.service\nsystemctl disable deploy-demo.service");
        assertThat(DocumentRenderer.renderDeploy(vars)).contains("systemctl disable deploy-demo.service");
    }
}
