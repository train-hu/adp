package com.ops.deploy.security;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.Result;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证端点：POST /api/auth/login。
 *
 * <p>安全要点：
 * - 密码错误与用户不存在返回同样的 401 消息，防止用户枚举；
 * - 用户不存在时也对固定的 dummy BCrypt hash 跑一次 {@code matches}，抹平两条路径的响应时间差，
 *   避免时序侧信道被用于枚举用户名；
 * - 密码用 BCrypt 校验（复用 spring-security-crypto，不引入完整 Spring Security）；
 * - disabled 用户返回 403；
 * - 成功签发 JWT 并返回 token/username/role，并把当前登录用户放入 request attribute
 *   （与 {@link JwtAuthFilter} 相同的 key），供登录成功后的审计切面读取操作人；
 * - 防爆破：同一 (IP, 用户名) 连续失败达阈值（默认 5 次）锁定一段时间（默认 15 分钟），
 *   锁定期内直接 403 拒绝（{@link LoginRateLimiter}，可经 deploy.login.* 配置）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final JwtTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    /**
     * 固定的 dummy BCrypt hash（类加载时计算一次）。用户不存在时用它跑一次 matches，
     * 使"用户不存在"与"用户存在但密码错"两条路径各执行一次 BCrypt 校验，抹平时序差。
     */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("dummy-password-for-timing");

    @Autowired
    public AuthController(UserRepository userRepository, JwtTokenService tokenService,
                          LoginRateLimiter rateLimiter) {
        this(userRepository, tokenService, new BCryptPasswordEncoder(), rateLimiter);
    }

    /** 可注入自定义依赖（便于单测：mock PasswordEncoder 校验 matches 调用次数、注入限流器）。 */
    public AuthController(UserRepository userRepository, JwtTokenService tokenService,
                          PasswordEncoder passwordEncoder, LoginRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    @Auditable(action = "AUTH_LOGIN", targetType = "USER")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = clientIp(request);
        // 防爆破：被锁定（连续失败达阈值）的 (ip, 用户名) 直接拒绝，不再走凭证校验
        Long remainingSec = rateLimiter.checkLock(ip, req.getUsername());
        if (remainingSec != null) {
            log.warn("login rejected (locked), ip={}, username={}, remaining={}s",
                    ip, req.getUsername(), remainingSec);
            throw new BizException(ErrorCode.FORBIDDEN,
                    "too many failed attempts, account locked for " + remainingSec + "s");
        }
        UserEntity user = userRepository.findByUsername(req.getUsername()).orElse(null);
        boolean badCredentials;
        if (user == null) {
            // 用户不存在：也对 dummy hash 跑一次 matches（返回必为 false），
            // 与"用户存在但密码错"一样执行一次 BCrypt，抹平时序差，防止用户名枚举
            badCredentials = !passwordEncoder.matches(req.getPassword(), DUMMY_HASH);
        } else {
            badCredentials = !passwordEncoder.matches(req.getPassword(), user.getPasswordHash());
        }
        if (badCredentials) {
            rateLimiter.recordFailure(ip, req.getUsername());
            // 与“用户不存在”使用同一消息，防用户枚举
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid username or password");
        }
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "user disabled");
        }
        rateLimiter.recordSuccess(ip, req.getUsername());
        String token = tokenService.issue(user.getId(), user.getUsername(), user.getRole());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        // 登录成功：把当前用户放入 request attribute（与 JwtAuthFilter 相同 key），
        // 使白名单路径下的审计切面（@Around 在 proceed 之后读 attribute）能拿到操作人。
        // 必须在 return 之前设置。
        request.setAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE,
                new AuthUser(user.getId(), user.getUsername(), user.getRole()));
        return Result.ok(data);
    }

    /** 客户端 IP（不解析 X-Forwarded-For：平台直连内网，取 socket 对端即可）。 */
    private static String clientIp(HttpServletRequest request) {
        return request == null ? "unknown" : request.getRemoteAddr();
    }

    /**
     * 登录请求体。toString 中密码打码，避免审计 detail（方法参数摘要）泄露明文密码。
     */
    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        @Override
        public String toString() {
            return "LoginRequest{username=" + username + ", password=***}";
        }
    }
}
