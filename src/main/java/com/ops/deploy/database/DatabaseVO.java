package com.ops.deploy.database;

import java.time.LocalDateTime;

/**
 * 数据库实例对外视图对象。
 *
 * <p><b>结构性杜绝凭证泄露</b>：本类不含任何 password / credential 字段，
 * 因此序列化到响应时不可能携带明文密码或密文。
 */
public class DatabaseVO {

    private Long id;
    private String name;
    private String dbType;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String status;
    private LocalDateTime lastCheckAt;
    private LocalDateTime createdAt;
    /** 创建人 userId（存量资源为 null）。 */
    private Long createdBy;

    public static DatabaseVO from(DatabaseEntity entity) {
        DatabaseVO vo = new DatabaseVO();
        vo.id = entity.getId();
        vo.name = entity.getName();
        vo.dbType = entity.getDbType();
        vo.host = entity.getHost();
        vo.port = entity.getPort();
        vo.databaseName = entity.getDatabaseName();
        vo.username = entity.getUsername();
        vo.status = entity.getStatus();
        vo.lastCheckAt = entity.getLastCheckAt();
        vo.createdAt = entity.getCreatedAt();
        vo.createdBy = entity.getCreatedBy();
        return vo;
    }

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
