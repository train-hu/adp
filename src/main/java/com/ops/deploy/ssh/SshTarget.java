package com.ops.deploy.ssh;

import com.ops.deploy.server.ServerVO;

/**
 * SSH 目标值对象：host/port/username/password。
 *
 * <p><b>凭证安全</b>：{@link #toString()} 对 password 打码（{@code password=***}），
 * 绝不输出明文——任何日志/异常信息中引用 SshTarget 时密码不会泄露。
 */
public final class SshTarget {

    public static final int DEFAULT_PORT = 22;

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public SshTarget(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    /**
     * 从 ServerVO 构建。password 由调用方经
     * {@code ServerService.getDecryptedPassword} 解密后传入（VO 本身不含凭证）。
     */
    public static SshTarget of(ServerVO server, String password) {
        int port = server.getSshPort() != null ? server.getSshPort() : DEFAULT_PORT;
        return new SshTarget(server.getIp(), port, server.getSshUser(), password);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    /**
     * 明文密码，仅供 SSH 握手使用；<b>切勿</b>写入日志或任何对外响应。
     */
    public String getPassword() {
        return password;
    }

    @Override
    public String toString() {
        return "SshTarget{host='" + host + "', port=" + port
                + ", username='" + username + "', password=***}";
    }
}
