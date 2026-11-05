package com.ops.deploy.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 部署目标服务器，映射 server_info 表。
 *
 * <p>authType / status 以 String 存储（对应枚举的 name，与 {@code UserEntity.role} 一致模式）。
 * 不映射 profile_json（JSONB，M1 暂不需要）。credentialCipher 为 AES-256-GCM 密文，
 * 明文凭证只经 {@link com.ops.deploy.crypto.CredentialCryptoService} 加解密，
 * 任何 VO/响应均不暴露。
 */
@Entity
@Table(name = "server_info")
public class ServerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false, length = 64)
    private String ip;

    @Column(name = "ssh_port", nullable = false)
    private Integer sshPort;

    @Column(name = "ssh_user", nullable = false, length = 64)
    private String sshUser;

    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType;

    @Column(name = "credential_cipher", nullable = false)
    private byte[] credentialCipher;

    @Column(name = "env_tag", nullable = false, length = 16)
    private String envTag;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "last_check_at")
    private LocalDateTime lastCheckAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getSshPort() {
        return sshPort;
    }

    public void setSshPort(Integer sshPort) {
        this.sshPort = sshPort;
    }

    public String getSshUser() {
        return sshUser;
    }

    public void setSshUser(String sshUser) {
        this.sshUser = sshUser;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getEnvTag() {
        return envTag;
    }

    public void setEnvTag(String envTag) {
        this.envTag = envTag;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public byte[] getCredentialCipher() {
        return credentialCipher;
    }

    public void setCredentialCipher(byte[] credentialCipher) {
        this.credentialCipher = credentialCipher;
    }

    public LocalDateTime getLastCheckAt() {
        return lastCheckAt;
    }

    public void setLastCheckAt(LocalDateTime lastCheckAt) {
        this.lastCheckAt = lastCheckAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
