package com.ops.deploy.aiassistant;

import com.ops.deploy.deploy.DeploymentDocumentEntity;
import com.ops.deploy.deploy.DeploymentDocumentRepository;
import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.security.AuthUser;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 部署文档知识库检索（关键词打分，无向量库）。
 *
 * <p>数据源：deployment_document 表中对应部署 status=SUCCESS 的文档（deploy/maintain 两份
 * markdown），且归属项目对当前用户可见（{@link ProjectAccessService#assertView} 语义，
 * 这里用 canView 批量过滤）。
 *
 * <p>算法：keyword 分词（ASCII 词 + CJK 连续串；CJK 串同时取 2-gram 兜底），
 * 对每篇文档按命中词元数打分（maintain 文档 +10% 权重，运维问答命中率高），
 * 取 top N，每篇截取首个命中位置前后片段 + 文档头（前 400 字符，含基本信息表）。
 */
@Component
public class KnowledgeBaseSearcher {

    static final int DEFAULT_LIMIT = 3;
    static final int MAX_LIMIT = 8;
    private static final int SNIPPET_RADIUS = 600;
    private static final int HEADER_LEN = 400;

    private final DeploymentRepository deploymentRepository;
    private final DeploymentDocumentRepository documentRepository;
    private final ProjectAccessService accessService;

    public KnowledgeBaseSearcher(DeploymentRepository deploymentRepository,
                                 DeploymentDocumentRepository documentRepository,
                                 ProjectAccessService accessService) {
        this.deploymentRepository = deploymentRepository;
        this.documentRepository = documentRepository;
        this.accessService = accessService;
    }

    /** 命中片段：来源标识 + 文档类型 + 正文片段。 */
    public record DocHit(long deploymentId, long projectId, String source, String docType, String snippet) {
    }

    /**
     * 检索可见项目中 SUCCESS 部署的文档。
     *
     * @param keyword 关键词（可含中文/空格）
     * @param limit   返回条数（≤ {@link #MAX_LIMIT}）
     * @return 按相关度降序；无命中返回空列表
     */
    public List<DocHit> search(String keyword, int limit, AuthUser user) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int n = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<String> tokens = tokenize(keyword);
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<DeploymentEntity> success = deploymentRepository.findByStatus("SUCCESS");
        List<DeploymentEntity> visible = success.stream()
                .filter(d -> canViewProject(d.getProjectId(), user))
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Long> ids = visible.stream().map(DeploymentEntity::getId).toList();
        Map<Long, DeploymentDocumentEntity> docs = documentRepository
                .findByDeploymentIdIn(ids).stream()
                .collect(java.util.stream.Collectors.toMap(DeploymentDocumentEntity::getDeploymentId, e -> e));
        List<ScoredHit> hits = new ArrayList<>();
        for (DeploymentEntity d : visible) {
            DeploymentDocumentEntity doc = docs.get(d.getId());
            if (doc == null) {
                continue;
            }
            scoreDocument(d, doc, tokens, hits, "deploy", 1.0);
            scoreDocument(d, doc, tokens, hits, "maintain", 1.1);
        }
        hits.sort((a, b) -> Long.compare(b.score, a.score));
        return hits.stream().limit(n).map(h ->
                new DocHit(h.deploymentId, h.projectId, h.source, h.docType, h.snippet)).toList();
    }

    private void scoreDocument(DeploymentEntity d, DeploymentDocumentEntity doc,
                               List<String> tokens, List<ScoredHit> hits,
                               String docType, double weight) {
        String text = "deploy".equals(docType) ? doc.getDeployMarkdown() : doc.getMaintainMarkdown();
        if (text == null || text.isBlank()) {
            return;
        }
        long score = 0;
        int firstHit = -1;
        for (String token : tokens) {
            int idx = text.indexOf(token);
            if (idx >= 0) {
                score += token.length() >= 4 ? 3 : 2; // 长词元（更具体）权重更高
                if (firstHit < 0 || idx < firstHit) {
                    firstHit = idx;
                }
            }
        }
        if (score == 0) {
            return;
        }
        String snippet;
        if (firstHit >= 0) {
            int start = Math.max(0, firstHit - SNIPPET_RADIUS / 2);
            int end = Math.min(text.length(), firstHit + SNIPPET_RADIUS);
            snippet = text.substring(start, end);
        } else {
            snippet = text.substring(0, Math.min(text.length(), SNIPPET_RADIUS));
        }
        String header = text.substring(0, Math.min(text.length(), HEADER_LEN));
        String source = "部署任务 #" + d.getId() + "（项目 ID " + d.getProjectId() + "）"
                + " 的" + ("deploy".equals(docType) ? "部署文档" : "维护文档");
        hits.add(new ScoredHit(d.getId(), d.getProjectId(), source, docType,
                header + "\n\n---\n" + snippet, (long) (score * weight)));
    }

    private boolean canViewProject(Long projectId, AuthUser user) {
        try {
            return accessService.canView(projectId, user);
        } catch (Exception e) {
            return false;
        }
    }

    /** 内部计分条目（含 long score，排序用）。 */
    private record ScoredHit(long deploymentId, long projectId, String source,
                             String docType, String snippet, long score) {
    }

    /**
     * 分词：ASCII 字母数字串（长度 ≥ 2）+ CJK 连续串（原串保留，另取 2-gram 兜底）。
     * 全部小写化用于 ASCII 匹配（CJK 不受影响）。
     */
    static List<String> tokenize(String keyword) {
        String lower = keyword.toLowerCase();
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 128) {
                ascii.append(c);
                continue;
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk.append(c);
                continue;
            }
            flushAscii(ascii, tokens);
            flushCjk(cjk, tokens);
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return new ArrayList<>(tokens);
    }

    private static void flushAscii(StringBuilder sb, Set<String> tokens) {
        if (sb.length() >= 2) {
            tokens.add(sb.toString());
        }
        sb.setLength(0);
    }

    private static void flushCjk(StringBuilder sb, Set<String> tokens) {
        String s = sb.toString();
        if (!s.isEmpty()) {
            tokens.add(s);
            if (s.length() >= 3) { // 长串取 2-gram 提升召回
                for (int i = 0; i + 2 <= s.length(); i++) {
                    tokens.add(s.substring(i, i + 2));
                }
            }
        }
        sb.setLength(0);
    }
}
