package com.ops.deploy.notify;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * webhook 配置创建请求：name/type/url 必填；enabled 可选（缺省 true）。
 */
public class WebhookCreateRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    /** 机器人类型：FEISHU / DINGTALK。 */
    @NotBlank
    @Size(max = 16)
    private String type;

    @NotBlank
    @Size(max = 1024)
    private String url;

    /** 是否启用推送（null 时默认 true）。 */
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
