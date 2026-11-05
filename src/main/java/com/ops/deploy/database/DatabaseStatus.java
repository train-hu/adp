package com.ops.deploy.database;

/**
 * 数据库连通状态。实体中以 String 存储（name()）。
 */
public enum DatabaseStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE
}
