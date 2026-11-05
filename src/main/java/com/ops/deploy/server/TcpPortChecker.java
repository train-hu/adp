package com.ops.deploy.server;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 基于 JDK 原生 {@link Socket} 的 TCP 端口探测实现。
 * 连接成功即认为端口可达（true），IOException（拒绝 / 超时 / 未知主机等）返回 false。
 */
@Component
public class TcpPortChecker implements PortChecker {

    @Override
    public boolean isReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
