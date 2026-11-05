package com.ops.deploy.database;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 已登记的数据库实例，映射 database_info 表。
 *
 * <p>dbType / status 以 String 存储（对应枚举 name，与 {@code ServerEntity} 同模式）。
 * passwordCipher 为 AES-256-GCM 密文（可空：个别场景数据库未设密码），
 * 明文只经 {@link com.ops.deploy.crypto.CredentialCryptoService} 加解密，任何 VO 不暴露。
 */
@Entity
@Table(name = "database_info")
public class DatabaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "db_type", nullable = false, length = 16)
    private String dbType;

    @Column(nullable = false, length = 128)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(name = "database_name", length = 128)
    private String databaseName;

    @Column(length = 128)
    private String username;

    @Column(name = "password_cipher")
    private byte[] passwordCipher;

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

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public byte[] getPasswordCipher() {
        return passwordCipher;
    }

    public void setPasswordCipher(byte[] passwordCipher) {
        this.passwordCipher = passwordCipher;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
