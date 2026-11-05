package com.ops.deploy.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则层类型识别结果（值对象）。
 *
 * <p>由 {@link TypeDetector} 产出，最终序列化为 JSON 存入 package_version.detected_json。
 * 字段：
 * <ul>
 *   <li>detectedType：JAVA_MAVEN/JAVA_GRADLE/NODE/PYTHON/STATIC_WEB/DOCKER/UNKNOWN</li>
 *   <li>buildTool：maven/gradle/npm/none 等</li>
 *   <li>artifactKind：构建产物形态（JAR/WAR；源码包/归档为 null）。jar 包安装后被
 *       mv 到 workdir 根（非 extract），Docker 构建上下文须为根目录</li>
 *   <li>entryPoint：入口（jar 主类 / 静态站 index.html 相对路径；不确定为 null）</li>
 *   <li>suggestedStartCmd：建议启动命令（规则给不出为 null，留给人工/AI）</li>
 *   <li>confidence：0-100 置信度</li>
 *   <li>evidence：命中的特征文件相对路径（落库 JSON 的一部分）</li>
 *   <li>suggestedPort / suggestedHealthPath：尽力推断，不确定为 null</li>
 *   <li>extractedConfigs：部署包内提取的配置文件内容（fileKey → 全文，如 compose/env/nginx），
 *       供上传时自动入库项目配置文件列表；无则为空 Map</li>
 *   <li>error：识别失败原因（无法解压等），正常识别为 null</li>
 * </ul>
 *
 * <p>对外读取视为不可变（{@link #getEvidence()} 返回不可变视图）；内部识别过程中可追加。
 */
public class DetectedResult {

    private String detectedType;
    private String buildTool;
    /** 构建产物形态（JAR/WAR；源码包/归档为 null）。 */
    private String artifactKind;
    private String entryPoint;
    private String suggestedStartCmd;
    private int confidence;
    private List<String> evidence = new ArrayList<>();
    private Integer suggestedPort;
    private String suggestedHealthPath;
    private String error;
    /** 部署包内提取的配置文件内容（fileKey → 全文），如 compose/env/nginx。 */
    private Map<String, String> extractedConfigs = new LinkedHashMap<>();

    public DetectedResult() {
    }

    /** 识别失败/未知结果工厂：type=UNKNOWN、低置信度、带 error。 */
    public static DetectedResult unknown(String error) {
        DetectedResult r = new DetectedResult();
        r.detectedType = "UNKNOWN";
        r.buildTool = "none";
        r.confidence = 0;
        r.error = error;
        return r;
    }

    public DetectedResult addEvidence(String relPath) {
        if (relPath != null && !evidence.contains(relPath)) {
            evidence.add(relPath);
        }
        return this;
    }

    public void addEvidence(String... relPaths) {
        for (String p : relPaths) {
            addEvidence(p);
        }
    }

    public String getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(String detectedType) {
        this.detectedType = detectedType;
    }

    public String getBuildTool() {
        return buildTool;
    }

    public void setBuildTool(String buildTool) {
        this.buildTool = buildTool;
    }

    public String getArtifactKind() {
        return artifactKind;
    }

    public void setArtifactKind(String artifactKind) {
        this.artifactKind = artifactKind;
    }

    public String getEntryPoint() {
        return entryPoint;
    }

    public void setEntryPoint(String entryPoint) {
        this.entryPoint = entryPoint;
    }

    public String getSuggestedStartCmd() {
        return suggestedStartCmd;
    }

    public void setSuggestedStartCmd(String suggestedStartCmd) {
        this.suggestedStartCmd = suggestedStartCmd;
    }

    public int getConfidence() {
        return confidence;
    }

    public void setConfidence(int confidence) {
        this.confidence = confidence;
    }

    public List<String> getEvidence() {
        return Collections.unmodifiableList(evidence);
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence != null ? evidence : new ArrayList<>();
    }

    public Integer getSuggestedPort() {
        return suggestedPort;
    }

    public void setSuggestedPort(Integer suggestedPort) {
        this.suggestedPort = suggestedPort;
    }

    public String getSuggestedHealthPath() {
        return suggestedHealthPath;
    }

    public void setSuggestedHealthPath(String suggestedHealthPath) {
        this.suggestedHealthPath = suggestedHealthPath;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Map<String, String> getExtractedConfigs() {
        return extractedConfigs != null ? extractedConfigs : new LinkedHashMap<>();
    }

    public void setExtractedConfigs(Map<String, String> extractedConfigs) {
        this.extractedConfigs = extractedConfigs != null ? extractedConfigs : new LinkedHashMap<>();
    }
}
