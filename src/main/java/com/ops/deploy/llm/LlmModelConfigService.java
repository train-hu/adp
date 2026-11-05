package com.ops.deploy.llm;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 模型配置管理（FR-11）：CRUD + 连接探测 + 对话测试。
 *
 * <p>api_key 走 {@link CredentialCryptoService} AES-256-GCM 加密落库，
 * VO 只返回脱敏掩码（前 6 位 + **** + 后 4 位，过短则全掩）。
 * 连接探测 = GET /models；对话测试 = 一次最小 chat/completions 调用。
 */
@Service
public class LlmModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmModelConfigService.class);

    private final LlmModelConfigRepository repository;
    private final CredentialCryptoService crypto;
    private final LlmChatClient client;

    public LlmModelConfigService(LlmModelConfigRepository repository,
                                 CredentialCryptoService crypto,
                                 LlmChatClient client) {
        this.repository = repository;
        this.crypto = crypto;
        this.client = client;
    }

    public record LlmModelVO(Long id, String name, String baseUrl, String model,
                             String apiKeyMask, boolean enabled, boolean isDefault,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 512) String baseUrl,
            @NotBlank @Size(max = 128) String model,
            @NotBlank String apiKey) {
    }

    public record UpdateRequest(String baseUrl, String model, String apiKey, Boolean enabled) {
    }

    public record TestResult(boolean ok, String message, List<String> availableModels) {
    }

    @Transactional
    public LlmModelVO create(CreateRequest req) {
        if (repository.existsByName(req.name())) {
            throw new BizException(ErrorCode.CONFLICT, "模型名称已存在: " + req.name());
        }
        LlmModelConfigEntity e = new LlmModelConfigEntity();
        e.setName(req.name());
        e.setBaseUrl(normalizeBaseUrl(req.baseUrl()));
        e.setModel(req.model());
        e.setApiKeyCipher(crypto.encrypt(req.apiKey()));
        e.setEnabled(true);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        try {
            e = repository.save(e);
        } catch (DataIntegrityViolationException ex) {
            throw new BizException(ErrorCode.CONFLICT, "模型名称已存在: " + req.name());
        }
        log.info("LLM model config created: id={} name={} baseUrl={} model={}",
                e.getId(), e.getName(), e.getBaseUrl(), e.getModel());
        return toVO(e);
    }

    public List<LlmModelVO> list() {
        return repository.findAllByOrderByCreatedAtAsc().stream().map(this::toVO).toList();
    }

    public LlmModelVO get(Long id) {
        return toVO(find(id));
    }

    @Transactional
    public LlmModelVO update(Long id, UpdateRequest req) {
        LlmModelConfigEntity e = find(id);
        if (req.baseUrl() != null) {
            e.setBaseUrl(normalizeBaseUrl(req.baseUrl()));
        }
        if (req.model() != null) {
            e.setModel(req.model());
        }
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            e.setApiKeyCipher(crypto.encrypt(req.apiKey()));
        }
        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
        e.setUpdatedAt(LocalDateTime.now());
        log.info("LLM model config updated: id={} enabled={}", id, e.isEnabled());
        return toVO(e);
    }

    @Transactional
    public void delete(Long id) {
        LlmModelConfigEntity e = find(id);
        repository.delete(e);
        log.info("LLM model config deleted: id={} name={}", id, e.getName());
    }

    /**
     * 设默认模型（互斥）：先清全部 is_default，再置目标为 true（同一事务，
     * 数据库部分唯一索引兜底一致性）。
     */
    @Transactional
    public LlmModelVO setDefault(Long id) {
        LlmModelConfigEntity target = find(id);
        repository.findAll().stream()
                .filter(e -> e.isDefault())
                .forEach(e -> e.setDefault(false));
        target.setDefault(true);
        target.setUpdatedAt(LocalDateTime.now());
        log.info("LLM model default set: id={} name={}", id, target.getName());
        return toVO(target);
    }

    /**
     * 助手/AI 部署统一模型解析：显式 modelId 必须存在且启用；
     * 缺省优先 is_default 的启用模型，其次第一个启用模型，无可用模型返回 null（由调用方降级）。
     */
    public LlmModelConfigEntity resolveAssistantModel(Long modelId) {
        if (modelId != null) {
            return repository.findById(modelId)
                    .filter(LlmModelConfigEntity::isEnabled)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "模型配置不存在或已禁用: " + modelId));
        }
        return repository.findAll().stream()
                .filter(LlmModelConfigEntity::isEnabled)
                .filter(LlmModelConfigEntity::isDefault)
                .findFirst()
                .orElseGet(() -> repository.findAll().stream()
                        .filter(LlmModelConfigEntity::isEnabled)
                        .findFirst().orElse(null));
    }

    /**
     * 连接探测：GET /models 成功即 ok（无论配置的 model 是否在列表中——
     * 网关可能按 key 过滤模型列表，以对话测试为准）。
     */
    public TestResult testConnection(Long id) {
        LlmModelConfigEntity e = find(id);
        String key = decryptKey(e);
        try {
            List<String> models = client.listModels(e.getBaseUrl(), key);
            return new TestResult(true, "连接成功", models);
        } catch (BizException ex) {
            return new TestResult(false, ex.getMessage(), List.of());
        }
    }

    /** 对话测试：一次最小 chat 调用，返回模型回复（截断展示）。 */
    public String testChat(Long id, String prompt) {
        LlmModelConfigEntity e = find(id);
        String key = decryptKey(e);
        String reply = client.chat(e.getBaseUrl(), key, e.getModel(),
                prompt == null || prompt.isBlank() ? "请回复 ok 以确认模型可用" : prompt);
        if (reply == null) {
            reply = "";
        }
        return reply.length() > 2000 ? reply.substring(0, 2000) + "..." : reply;
    }

    private LlmModelConfigEntity find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id));
    }

    private String decryptKey(LlmModelConfigEntity e) {
        return crypto.decrypt(e.getApiKeyCipher());
    }

    private LlmModelVO toVO(LlmModelConfigEntity e) {
        String key = decryptKey(e);
        return new LlmModelVO(e.getId(), e.getName(), e.getBaseUrl(), e.getModel(),
                mask(key), e.isEnabled(), e.isDefault(), e.getCreatedAt(), e.getUpdatedAt());
    }

    /** 前 6 位 + **** + 后 4 位；长度 ≤ 12 时全掩（避免短 key 近乎全暴露）。 */
    static String mask(String apiKey) {
        if (apiKey == null || apiKey.length() <= 12) {
            return "****";
        }
        return apiKey.substring(0, 6) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 去尾斜杠；不强制补 /v1（客户端 completionsUrl/modelsUrl 会自动补）。 */
    private static String normalizeBaseUrl(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
