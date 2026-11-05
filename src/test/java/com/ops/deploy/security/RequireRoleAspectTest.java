package com.ops.deploy.security;

import com.ops.deploy.common.BizException;
import com.ops.deploy.user.Role;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequireRoleAspectTest {

    @Mock
    private ProceedingJoinPoint pjp;

    @Mock
    private MethodSignature signature;

    private RequireRoleAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RequireRoleAspect();
        when(pjp.getSignature()).thenReturn(signature);
    }

    private void useMethod(String name) throws NoSuchMethodException {
        when(signature.getMethod()).thenReturn(SampleService.class.getDeclaredMethod(name));
    }

    private AuthUser user(Role role) {
        return new AuthUser(1L, "tester", role.name());
    }

    @Test
    void adminAccessOpsAnnotated_shouldProceed() throws Throwable {
        useMethod("opsAction");
        when(pjp.getArgs()).thenReturn(new Object[]{user(Role.ADMIN)});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.checkRole(pjp);

        assertThat(result).isEqualTo("ok");
        verify(pjp).proceed();
    }

    @Test
    void opsAccessOpsAnnotated_shouldProceed() throws Throwable {
        useMethod("opsAction");
        when(pjp.getArgs()).thenReturn(new Object[]{user(Role.OPS)});
        when(pjp.proceed()).thenReturn("ok");

        assertThat(aspect.checkRole(pjp)).isEqualTo("ok");
        verify(pjp).proceed();
    }

    @Test
    void adminAccessAdminAnnotated_shouldProceed() throws Throwable {
        useMethod("adminAction");
        when(pjp.getArgs()).thenReturn(new Object[]{user(Role.ADMIN)});
        when(pjp.proceed()).thenReturn("ok");

        assertThat(aspect.checkRole(pjp)).isEqualTo("ok");
        verify(pjp).proceed();
    }

    @Test
    void opsAccessAdminAnnotated_shouldThrowForbiddenAndNotProceed() throws Throwable {
        useMethod("adminAction");
        when(pjp.getArgs()).thenReturn(new Object[]{user(Role.OPS)});

        assertThatThrownBy(() -> aspect.checkRole(pjp))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
        verify(pjp, never()).proceed();
    }

    @Test
    void noAnnotation_shouldProceedByDefault() throws Throwable {
        useMethod("publicAction");
        when(pjp.proceed()).thenReturn("ok");

        assertThat(aspect.checkRole(pjp)).isEqualTo("ok");
        verify(pjp).proceed();
    }

    static class SampleService {

        @RequireRole(Role.OPS)
        public String opsAction() {
            return "ok";
        }

        @RequireRole(Role.ADMIN)
        public String adminAction() {
            return "ok";
        }

        public String publicAction() {
            return "ok";
        }
    }
}
