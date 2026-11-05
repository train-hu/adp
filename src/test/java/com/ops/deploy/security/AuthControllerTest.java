package com.ops.deploy.security;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.GlobalExceptionHandler;
import com.ops.deploy.user.Role;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private JwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService();
        AuthController controller = new AuthController(userRepository, tokenService, new LoginRateLimiter());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String loginJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void login_validPassword_shouldReturn200WithTokenUsernameRole() throws Exception {
        UserEntity user = new UserEntity("alice", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"), Role.OPS.name());
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(user));

        MvcResult mvcResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("OPS"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();

        // 返回的 token 应可被解析
        String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = extractToken(body);
        AuthUser parsed = tokenService.parse(token);
        assertThat(parsed.getUsername()).isEqualTo("alice");
        assertThat(parsed.getRole()).isEqualTo("OPS");
    }

    @Test
    void login_wrongPassword_shouldReturn401() throws Exception {
        UserEntity user = new UserEntity("alice", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"), Role.OPS.name());
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "wrong-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("invalid username or password"));
    }

    @Test
    void login_userNotFound_shouldReturn401WithSameMessageAsWrongPassword() throws Exception {
        when(userRepository.findByUsername("ghost")).thenReturn(java.util.Optional.empty());

        MvcResult notFound = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("ghost", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andReturn();

        // 再准备一个用户存在但密码错误的场景，比较 message 必须一致（防用户枚举）
        UserEntity user = new UserEntity("alice", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"), Role.OPS.name());
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(user));
        MvcResult wrongPass = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "wrong-pass")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String m1 = notFound.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String m2 = wrongPass.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(m1).contains("invalid username or password");
        assertThat(m2).contains("invalid username or password");
    }

    @Test
    void login_disabledUser_shouldReturn403() throws Exception {
        UserEntity user = new UserEntity("bob", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"), Role.OPS.name());
        user.setEnabled(false);
        when(userRepository.findByUsername("bob")).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("bob", "secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 时序侧信道修复验证：用户不存在时也必须对 dummy hash 调用一次 matches，
     * 与"用户存在但密码错"路径一样各执行一次 BCrypt 校验，抹平时序差。
     * 用 mock PasswordEncoder 精确断言 matches 调用次数（时序本身难以稳定断言）。
     */
    @Test
    void login_userNotFound_shouldStillRunBcryptOnceViaDummyHash() throws Exception {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userRepository.findByUsername("ghost")).thenReturn(java.util.Optional.empty());
        // dummy hash 必然校验失败 → 401
        when(encoder.matches(any(String.class), any(String.class))).thenReturn(false);

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(userRepository, tokenService, encoder,
                        new LoginRateLimiter()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("ghost", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid username or password"));

        // 关键断言：用户不存在路径也调用了一次 matches（dummy hash）
        verify(encoder, times(1)).matches(any(String.class), any(String.class));
    }

    /**
     * 时序侧信道修复验证：用户存在但密码错时同样调用一次 matches，
     * 与"用户不存在"路径的调用次数一致（各 1 次），两条路径耗时特征对齐。
     */
    @Test
    void login_wrongPassword_shouldRunBcryptOnce() throws Exception {
        UserEntity user = new UserEntity("alice",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"),
                Role.OPS.name());
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(user));

        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.matches(any(String.class), any(String.class))).thenReturn(false);

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(userRepository, tokenService, encoder,
                        new LoginRateLimiter()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "wrong-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid username or password"));

        verify(encoder, times(1)).matches(any(String.class), any(String.class));
    }

    /**
     * 登录审计操作人修复验证：登录成功后 controller 把当前用户放入 request attribute
     * （与 JwtAuthFilter 相同 key），白名单路径下的审计切面即可读到操作人。
     */
    @Test
    void login_success_shouldInjectCurrentUserAttributeForAudit() throws Exception {
        UserEntity user = new UserEntity("alice",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"),
                Role.OPS.name());
        user.setId(8L);
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(user));

        MvcResult mvcResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "secret")))
                .andExpect(status().isOk())
                .andReturn();

        AuthUser injected = (AuthUser) mvcResult.getRequest()
                .getAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE);
        assertThat(injected).isNotNull();
        assertThat(injected.getUserId()).isEqualTo(8L);
        assertThat(injected.getUsername()).isEqualTo("alice");
        assertThat(injected.getRole()).isEqualTo("OPS");
    }

    /**
     * 防爆破验证：同一 (IP, 用户名) 连续失败达阈值（默认 5 次）后，
     * 即使后续输入正确密码也被锁定拒绝（403），且不再访问凭证仓储。
     */
    @Test
    void login_afterFiveFailures_shouldLockEvenWithCorrectPassword() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter();
        AuthController lockedController =
                new AuthController(userRepository, tokenService, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(), limiter);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(lockedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // 连续 5 次密码错误
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("alice", "wrong-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        // 第 6 次：正确密码也应被锁定拒绝（MockMvc 默认 remoteAddr=127.0.0.1，前后一致）
        long lookupsBefore = org.mockito.Mockito.mockingDetails(userRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("findByUsername")).count();
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("locked")));
        long lookupsAfter = org.mockito.Mockito.mockingDetails(userRepository).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("findByUsername")).count();
        // 锁定期内不再查库校验凭证
        assertThat(lookupsAfter).isEqualTo(lookupsBefore);
    }

    /**
     * 防爆破验证：登录成功清零失败计数——成功前失败 4 次 + 成功后再失败 4 次，
     * 不应触发锁定（第 5 次失败仍返回 401 而非 403）。
     */
    @Test
    void login_success_shouldResetFailureCounter() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter();
        AuthController c =
                new AuthController(userRepository, tokenService, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(), limiter);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(c)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UserEntity user = new UserEntity("alice",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"),
                Role.OPS.name());
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(user));

        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("alice", "wrong-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "secret")))
                .andExpect(status().isOk());
        // 计数已清零：再失败 4 次仍只是 401，不锁定
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("alice", "again-wrong-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice", "secret")))
                .andExpect(status().isOk());
    }

    private static String extractToken(String body) {
        int idx = body.indexOf("\"token\":\"");
        int start = idx + "\"token\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
