package com.ops.deploy.ssh;

import com.ops.deploy.server.ServerVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshTarget 纯逻辑单测：值对象语义 + 凭证打码（toString 绝不出现明文密码）。
 */
class SshTargetTest {

    private static ServerVO serverVO(String ip, Integer port, String user) {
        ServerVO vo = new ServerVO();
        vo.setId(7L);
        vo.setName("web-01");
        vo.setIp(ip);
        vo.setSshPort(port);
        vo.setSshUser(user);
        vo.setAuthType("PASSWORD");
        return vo;
    }

    @Test
    void ofServer_buildsTargetFromVo() {
        SshTarget t = SshTarget.of(serverVO("10.1.2.3", 2222, "deploy"), "p@ss w0rd");

        assertThat(t.getHost()).isEqualTo("10.1.2.3");
        assertThat(t.getPort()).isEqualTo(2222);
        assertThat(t.getUsername()).isEqualTo("deploy");
        assertThat(t.getPassword()).isEqualTo("p@ss w0rd");
    }

    @Test
    void ofServer_nullPort_fallsBackToDefault22() {
        assertThat(SshTarget.of(serverVO("10.1.2.3", null, "u"), "pw").getPort()).isEqualTo(22);
    }

    @Test
    void toString_masksSimplePassword() {
        SshTarget t = SshTarget.of(serverVO("10.1.2.3", 22, "deploy"), "p@ss w0rd");

        assertThat(t.toString()).contains("password=***").doesNotContain("p@ss w0rd");
    }

    @Test
    void toString_masksPasswordWithSpecialChars() {
        String nasty = "p@$$'\"\\n\\t\r|;&<>(){}[]*?~^` 空格 tab\tline\nbreak";
        SshTarget t = SshTarget.of(serverVO("10.1.2.3", 22, "deploy"), nasty);

        assertThat(t.toString()).contains("password=***");
        assertThat(t.toString()).doesNotContain("p@$$");
        assertThat(t.toString()).doesNotContain("空格");
        assertThat(t.toString()).doesNotContain("\n");
    }

    @Test
    void toString_masksNullPassword() {
        SshTarget t = SshTarget.of(serverVO("10.1.2.3", 22, "deploy"), null);

        assertThat(t.toString()).contains("password=***").doesNotContain("null");
    }
}
