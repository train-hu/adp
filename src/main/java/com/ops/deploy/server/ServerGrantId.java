package com.ops.deploy.server;

import java.io.Serializable;

/**
 * 服务器分配联合主键载体（server_id + user_id），供 {@link ServerGrantEntity}
 * 的 @IdClass 与 {@link ServerGrantRepository} 的复合主键查询使用。
 */
public class ServerGrantId implements Serializable {

    private Long serverId;
    private Long userId;

    public ServerGrantId() {
    }

    public ServerGrantId(Long serverId, Long userId) {
        this.serverId = serverId;
        this.userId = userId;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServerGrantId)) {
            return false;
        }
        ServerGrantId that = (ServerGrantId) o;
        return serverId != null && serverId.equals(that.serverId)
                && userId != null && userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return (serverId == null ? 0 : serverId.hashCode())
                * 31 + (userId == null ? 0 : userId.hashCode());
    }
}
