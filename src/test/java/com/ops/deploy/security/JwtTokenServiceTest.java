package com.ops.deploy.security;

import com.ops.deploy.common.BizException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private final JwtTokenService service = new JwtTokenService();

    @Test
    void issue_thenParse_shouldRoundtripClaims() {
        String token = service.issue(7L, "alice", "OPS");

        AuthUser user = service.parse(token);

        assertThat(user.getUserId()).isEqualTo(7L);
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getRole()).isEqualTo("OPS");
    }

    @Test
    void tamperedPayload_shouldBeRejected() {
        String token = service.issue(7L, "alice", "OPS");
        // 篡改 payload（base64url 中 'l' -> 'a'），签名必然失配
        String[] parts = token.split("\\.");
        String tampered = "a" + parts[1].substring(1);
        String forged = parts[0] + "." + tampered + "." + parts[2];

        assertThatThrownBy(() -> service.parse(forged))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(401));
    }

    @Test
    void expiredToken_shouldBeRejected() {
        String expired = service.issue(7L, "alice", "OPS", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.parse(expired))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(401));
    }

    @Test
    void malformedToken_shouldBeRejected() {
        assertThatThrownBy(() -> service.parse("not-a-jwt"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void enforceOn_placeholderSecret_shouldFailFast() {
        // 公开占位值 == DEFAULT_SECRET，强校验下拒绝初始化
        assertThatThrownBy(() -> new JwtTokenService(JwtTokenService.DEFAULT_SECRET, 24L, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforceOn_shortSecret_shouldFailFast() {
        assertThatThrownBy(() -> new JwtTokenService("tooshort-secret", 24L, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforceOn_emptySecret_shouldFailFast() {
        assertThatThrownBy(() -> new JwtTokenService("", 24L, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforceOn_strongSecret_shouldIssueAndParse() {
        String strong = "0123456789abcdef0123456789abcdef"; // 32 字节、非占位值
        JwtTokenService svc = new JwtTokenService(strong, 24L, true);

        String token = svc.issue(3L, "carol", "OPS");
        AuthUser user = svc.parse(token);

        assertThat(user.getUserId()).isEqualTo(3L);
        assertThat(user.getUsername()).isEqualTo("carol");
        assertThat(user.getRole()).isEqualTo("OPS");
    }

    @Test
    void enforceOff_weakSecret_shouldBeAllowedForLocalDev() {
        // 向后兼容：本地/测试关闭强校验后允许使用占位弱密钥
        JwtTokenService svc = new JwtTokenService(JwtTokenService.DEFAULT_SECRET, 24L, false);

        String token = svc.issue(5L, "dave", "OPS");
        AuthUser user = svc.parse(token);

        assertThat(user.getUserId()).isEqualTo(5L);
        assertThat(user.getUsername()).isEqualTo("dave");
    }
}
