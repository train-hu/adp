package com.ops.deploy.audit;

import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditAspectTest {

    @Mock
    private AuditRepository auditRepository;

    @Mock
    private ProceedingJoinPoint pjp;

    @Mock
    private MethodSignature signature;

    @Mock
    private HttpServletRequest httpServletRequest;

    private AuditAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AuditAspect(auditRepository);
        lenient().when(pjp.getSignature()).thenReturn(signature);
        lenient().when(httpServletRequest.getRequestURI()).thenReturn("/api/test");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void useMethod(String name) throws NoSuchMethodException {
        Method method = SampleService.class.getDeclaredMethod(name);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getName()).thenReturn(method.getName());
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpServletRequest));
    }

    private AuthUser user(String username, String role) {
        return new AuthUser(42L, username, role);
    }

    @Test
    void auditableWithUserArg_shouldSaveAuditWithCorrectFields() throws Throwable {
        useMethod("createThing");
        bindRequest();
        when(httpServletRequest.getRemoteAddr()).thenReturn("10.1.2.3");
        when(pjp.getArgs()).thenReturn(new Object[]{user("alice", "OPS"), "hello"});
        when(pjp.proceed()).thenReturn("created");

        Object result = aspect.audit(pjp);

        assertThat(result).isEqualTo("created");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getRole()).isEqualTo("OPS");
        assertThat(saved.getAction()).isEqualTo("TEST_ACTION");
        assertThat(saved.getTargetType()).isEqualTo("THING");
        assertThat(saved.getTargetId()).isNull();
        assertThat(saved.getIp()).isEqualTo("10.1.2.3");
        assertThat(saved.getDetail()).contains("createThing");
        assertThat(saved.getDetail()).contains("hello");
        assertThat(saved.getDetail()).hasSizeLessThanOrEqualTo(2000);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void xForwardedFor_firstHop_shouldBeUsedAsClientIp() throws Throwable {
        useMethod("createThing");
        bindRequest();
        when(httpServletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(httpServletRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1");
        when(pjp.getArgs()).thenReturn(new Object[]{user("alice", "OPS")});
        when(pjp.proceed()).thenReturn("ok");

        aspect.audit(pjp);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void noUserInArgsNoRequestAttribute_shouldStillSaveWithNullIdentity() throws Throwable {
        useMethod("createThing");
        bindRequest();
        when(httpServletRequest.getRemoteAddr()).thenReturn("192.168.0.9");
        when(pjp.getArgs()).thenReturn(new Object[]{});
        when(pjp.proceed()).thenReturn("ok");

        aspect.audit(pjp);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.getUsername()).isNull();
        assertThat(saved.getRole()).isNull();
        assertThat(saved.getAction()).isEqualTo("TEST_ACTION");
    }

    /**
     * 登录审计场景：方法参数中无 AuthUser，但 request attribute 中已有 AuthUser
     * （登录成功后由 AuthController 写入，与 JwtAuthFilter 相同 key），
     * 切面应回退到 request attribute 读取操作人并正确落库 username/role/userId。
     */
    @Test
    void noUserInArgsButRequestAttributeHasUser_shouldSaveIdentityFromRequestAttribute() throws Throwable {
        useMethod("createThing");
        bindRequest();
        when(httpServletRequest.getRemoteAddr()).thenReturn("10.9.8.7");
        // 模拟登录成功后 controller 已把当前用户写入 request attribute
        when(httpServletRequest.getAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE))
                .thenReturn(user("alice", "OPS"));
        when(pjp.getArgs()).thenReturn(new Object[]{});
        when(pjp.proceed()).thenReturn("ok");

        aspect.audit(pjp);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getRole()).isEqualTo("OPS");
        assertThat(saved.getAction()).isEqualTo("TEST_ACTION");
    }

    @Test
    void noHttpRequestBound_shouldStillSaveWithoutIp() throws Throwable {
        useMethod("createThing");
        // 不绑定请求上下文
        when(pjp.getArgs()).thenReturn(new Object[]{user("bob", "OPS")});
        when(pjp.proceed()).thenReturn("ok");

        aspect.audit(pjp);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getIp()).isNull();
        assertThat(captor.getValue().getUsername()).isEqualTo("bob");
    }

    @Test
    void auditSaveThrows_shouldNotPropagateToMainFlow() throws Throwable {
        useMethod("createThing");
        bindRequest();
        when(httpServletRequest.getRemoteAddr()).thenReturn("10.1.2.3");
        when(pjp.getArgs()).thenReturn(new Object[]{user("alice", "OPS")});
        when(pjp.proceed()).thenReturn("main-result");
        doThrow(new RuntimeException("db down")).when(auditRepository).save(any(AuditLog.class));

        Object result = aspect.audit(pjp);

        assertThat(result).isEqualTo("main-result");
    }

    @Test
    void methodWithoutAuditable_shouldProceedWithoutSaving() throws Throwable {
        useMethod("plainMethod");
        when(pjp.proceed()).thenReturn("plain");

        Object result = aspect.audit(pjp);

        assertThat(result).isEqualTo("plain");
        verify(auditRepository, never()).save(any(AuditLog.class));
    }

    static class SampleService {

        @Auditable(action = "TEST_ACTION", targetType = "THING")
        public String createThing() {
            return "ok";
        }

        public String plainMethod() {
            return "plain";
        }
    }
}
