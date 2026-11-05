package com.ops.deploy.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.common.Result;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * JWT 认证过滤器：拦截 /api/**（白名单除外），校验 Bearer token。
 *
 * <p>无 token / 无效 / 过期 → 直接写 401 JSON 响应（与 {@link com.ops.deploy.common.Result}
 * 结构一致）。注意：过滤器抛出的异常不会被 {@code @RestControllerAdvice} 捕获，
 * 因此这里直接写响应而非抛 {@link com.ops.deploy.common.BizException}。
 *
 * <p>验签通过后回查 DB：用户不存在（被删除/重建）或角色与 token 不一致 → 401。
 * 签名校验保证 token 未被篡改，DB 回查保证用户仍有效（无状态 JWT 不感知用户删除）。
 *
 * <p>token 有效 → 把 {@link AuthUser} 放入 request attribute
 * （{@link #CURRENT_USER_ATTRIBUTE}），供 {@code @CurrentUser} 解析器与审计切面读取；
 * 请求结束由容器自动清理 attribute，无需手动清理。
 */
@Component
public class JwtAuthFilter implements Filter {

    public static final String CURRENT_USER_ATTRIBUTE = "com.ops.deploy.security.AUTH_USER";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> WHITELIST = Set.of("/api/auth/login", "/api/ping");

    private final JwtTokenService tokenService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtTokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getServletPath();

        if (path == null || !path.startsWith("/api/") || WHITELIST.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = req.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
                ? header.substring("Bearer ".length()).trim()
                : null;
        if (token == null || token.isEmpty()) {
            writeUnauthorized(resp);
            return;
        }

        AuthUser user;
        try {
            user = tokenService.parse(token);
        } catch (RuntimeException e) {
            writeUnauthorized(resp);
            return;
        }
        // 回查 DB：用户不存在或角色与 token 不一致（用户被删/重建、角色变更）→ 401
        if (!userExistsWithRole(user)) {
            writeUnauthorized(resp);
            return;
        }
        req.setAttribute(CURRENT_USER_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    private boolean userExistsWithRole(AuthUser user) {
        Optional<UserEntity> entity =
                user.getUserId() != null ? userRepository.findById(user.getUserId()) : Optional.empty();
        return entity.isPresent()
                && user.getRole() != null
                && user.getRole().equals(entity.get().getRole());
    }

    private void writeUnauthorized(HttpServletResponse resp) throws IOException {
        resp.setStatus(401);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(
                MAPPER.writeValueAsString(Result.fail(ErrorCode.UNAUTHORIZED, "unauthorized")));
    }
}
