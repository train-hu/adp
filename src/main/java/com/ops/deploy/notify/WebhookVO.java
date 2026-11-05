package com.ops.deploy.notify;

import java.time.LocalDateTime;

/**
 * webhook 配置视图（实体 → 前端）。
 */
public class WebhookVO {

    private Long id;
    private String name;
    private String type;
    private String url;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WebhookVO from(WebhookConfigEntity e) {
        WebhookVO vo = new WebhookVO();
        vo.id = e.getId();
        vo.name = e.getName();
        vo.type = e.getType();
        vo.url = e.getUrl();
        vo.enabled = e.isEnabled();
        vo.createdAt = e.getCreatedAt();
        vo.updatedAt = e.getUpdatedAt();
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
