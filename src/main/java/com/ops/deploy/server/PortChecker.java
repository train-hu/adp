package com.ops.deploy.server;

/**
 * TCP 端口可达性探测接口。抽象为接口便于单测 mock（不触发真实网络）。
 *
 * <p>连通性测试 = TCP 端口可达性（防火墙/端口层面）。真正的 SSH 握手 + 认证
 * 校验在 Task 4（SshExecutor）接入并增强，届时替换实现。
 */
public interface PortChecker {

    /**
     * 探测 host:port 在 timeoutMs 内是否 TCP 可达。
     *
     * @return 可达返回 true；连接失败 / 超时返回 false
     */
    boolean isReachable(String host, int port, int timeoutMs);
}
