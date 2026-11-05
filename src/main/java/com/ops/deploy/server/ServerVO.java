package com.ops.deploy.server;

import java.time.LocalDateTime;

/**
 * 服务器对外视图对象。
 *
 * <p><b>结构性杜绝凭证泄露</b>：本类不含任何 password / credential 字段，
 * 因此序列化到响应时不可能携带明文密码或密文。
 */
public class ServerVO {

    private Long id;
    private String name;
    private String ip;
    private Integer sshPort;
    private String sshUser;
    private String authType;
    private String envTag;
    private String status;
    private LocalDateTime lastCheckAt;
    private LocalDateTime createdAt;
    /** 创建人 userId（存量资源为 null）。 */
    private Long createdBy;

    public static ServerVO from(ServerEntity entity) {
        ServerVO vo = new ServerVO();
        vo.id = entity.getId();
        vo.name = entity.getName();
        vo.ip = entity.getIp();
        vo.sshPort = entity.getSshPort();
        vo.sshUser = entity.getSshUser();
        vo.authType = entity.getAuthType();
        vo.envTag = entity.getEnvTag();
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
