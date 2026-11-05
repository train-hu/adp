package com.ops.deploy.notify;

import jakarta.validation.constraints.Size;

/**
 * webhook 配置更新请求：各字段均可选，null 表示不修改对应字段
 * （与 {@code UserUpdateRequest} 同款部分更新语义）。
 */
public class WebhookUpdateRequest {

    @Size(max = 128)
    private String name;

    @Size(max = 16)
    private String type;

    @Size(max = 1024)
    private String url;

    private Boolean enabled;

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

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
