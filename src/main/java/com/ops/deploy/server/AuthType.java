package com.ops.deploy.server;

/**
 * SSH 认证方式。实体中以 String 存储（name()）。M1 仅实现 PASSWORD。
 */
public enum AuthType {
    PASSWORD,
    PUBLICKEY
}
