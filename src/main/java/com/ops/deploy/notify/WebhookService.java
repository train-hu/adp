package com.ops.deploy.notify;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * webhook 配置管理（仅 ADMIN）：CRUD + type 白名单校验。
 */
@Service
public class WebhookService {

    /** 允许的机器人类型白名单（实体 type 列 VARCHAR(16)，按大写存储）。 */
    private static final List<String> ALLOWED_TYPES = List.of("FEISHU", "DINGTALK");

    private final WebhookConfigRepository repository;

    public WebhookService(WebhookConfigRepository repository) {
        this.repository = repository;
    }

    /** 全部 webhook 配置（id 升序）。 */
    public List<WebhookVO> list() {
        return repository.findAll(org.springframework.data.domain.Sort.by("id"))
                .stream()
                .map(WebhookVO::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public WebhookVO create(WebhookCreateRequest req) {
        WebhookConfigEntity entity = new WebhookConfigEntity();
        entity.setName(req.getName().trim());
        entity.setType(parseType(req.getType()));
        entity.setUrl(req.getUrl().trim());
        entity.setEnabled(req.getEnabled() == null || req.getEnabled());
        entity.setCreatedAt(LocalDateTime.now());
        repository.save(entity);
        return WebhookVO.from(entity);
    }

    @Transactional
    public WebhookVO update(Long id, WebhookUpdateRequest req) {
        WebhookConfigEntity entity = require(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            entity.setName(req.getName().trim());
        }
        if (req.getType() != null && !req.getType().isBlank()) {
            entity.setType(parseType(req.getType()));
        }
        if (req.getUrl() != null && !req.getUrl().isBlank()) {
            entity.setUrl(req.getUrl().trim());
        }
        if (req.getEnabled() != null) {
            entity.setEnabled(req.getEnabled());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        return WebhookVO.from(entity);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    /** 加载配置（不存在抛 404）。 */
    public WebhookConfigEntity require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "webhook config not found"));
    }

    /** type 白名单校验：FEISHU / DINGTALK（大小写不敏感，按大写存储）。 */
    static String parseType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        if (!ALLOWED_TYPES.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "invalid webhook type: " + type + " (allowed: FEISHU, DINGTALK)");
        }
        return normalized;
    }
}
