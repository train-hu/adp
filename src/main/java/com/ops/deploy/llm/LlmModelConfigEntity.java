package com.ops.deploy.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * LLM 模型接入配置，映射 llm_model_config 表（M2 AI 能力，FR-11）。
 *
 * <p>统一 OpenAI 兼容协议（{@code base_url} 指向任意 /v1 端点 + {@code model} + Bearer key），
 * 云端 API 与本地私有化部署（如 192.168.x.x:30088）同构接入。
 * api_key 以 AES-256-GCM 密文存储（与 server_info.credential_cipher 同一加密体系，
 * 见 {@link com.ops.deploy.crypto.CredentialCryptoService}），明文只经
 * {@link LlmChatClient} 调用时解密，任何 VO/响应均不暴露。
 */
@Entity
@Table(name = "llm_model_config")
public class LlmModelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "api_key_cipher", nullable = false)
    private byte[] apiKeyCipher;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 默认模型标记（V17；数据库部分唯一索引保证至多一个为 true）。 */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public byte[] getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(byte[] apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
