package com.ops.deploy.server;

/**
 * 服务器连通状态。实体中以 String 存储（name()）。
 */
public enum ServerStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE
}
