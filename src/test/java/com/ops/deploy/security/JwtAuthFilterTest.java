package com.ops.deploy.security;

import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private JwtTokenService tokenService;
    private UserRepository userRepository;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService();
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthFilter(tokenService, userRepository);
    }

    private void stubExistingUser(long id, String role) {
        UserEntity entity = mock(UserEntity.class);
        when(entity.getRole()).thenReturn(role);
        when(userRepository.findById(id)).thenReturn(Optional.of(entity));
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.setServletPath(path);
        req.setContextPath("");
        return req;
    }

    @Test
    void noToken_shouldRespond401JsonAndNotProceed() throws Exception {
        MockHttpServletRequest req = request("/api/projects");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentType()).contains("application/json");
        assertThat(resp.getContentAsString()).contains("\"code\":401");
        verifyNoInteractions(chain);
    }

    @Test
    void invalidToken_shouldRespond401JsonAndNotProceed() throws Exception {
        MockHttpServletRequest req = request("/api/projects");
        req.addHeader("Authorization", "Bearer garbage.token.here");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("\"code\":401");
        verifyNoInteractions(chain);
    }

    @Test
    void expiredToken_shouldRespond401JsonAndNotProceed() throws Exception {
        String expired = tokenService.issue(9L, "carol", "OPS", java.time.Instant.now().minusSeconds(60));
        MockHttpServletRequest req = request("/api/projects");
        req.addHeader("Authorization", "Bearer " + expired);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void validToken_shouldProceedAndInjectAttribute() throws Exception {
        String token = tokenService.issue(9L, "carol", "OPS");
        stubExistingUser(9L, "OPS");
        MockHttpServletRequest req = request("/api/projects");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        AuthUser injected = (AuthUser) req.getAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE);
        assertThat(injected).isNotNull();
        assertThat(injected.getUserId()).isEqualTo(9L);
        assertThat(injected.getUsername()).isEqualTo("carol");
        assertThat(injected.getRole()).isEqualTo("OPS");
    }

    @Test
    void validTokenButUserDeleted_shouldRespond401() throws Exception {
        String token = tokenService.issue(9L, "carol", "OPS");
        when(userRepository.findById(9L)).thenReturn(Optional.empty());
        MockHttpServletRequest req = request("/api/projects");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("\"code\":401");
        verifyNoInteractions(chain);
    }

    @Test
    void validTokenButRoleChanged_shouldRespond401() throws Exception {
        String token = tokenService.issue(9L, "carol", "OPS");
        stubExistingUser(9L, "ADMIN"); // DB 中角色已变为 ADMIN，token 仍是 OPS
        MockHttpServletRequest req = request("/api/projects");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void whitelistedPaths_shouldBypassAuth() throws Exception {
        String[] whitelisted = {"/api/auth/login", "/api/ping"};
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        for (String path : whitelisted) {
            MockHttpServletRequest req = request(path);
            filter.doFilter(req, resp, chain);
            verify(chain).doFilter(req, resp);
            assertThat(req.getAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE)).isNull();
        }
    }

    @Test
    void nonApiPaths_shouldBypassAuth() throws Exception {
        MockHttpServletRequest req = request("/error");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }
}
