package com.ops.deploy.aiassistant;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.dashboard.DashboardService;
import com.ops.deploy.database.DatabaseService;
import com.ops.deploy.deploy.DeploymentService;
import com.ops.deploy.deploy.ServiceInstanceService;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectService;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.ResourceAccessService;
import com.ops.deploy.security.ResourceGrantVO;
import com.ops.deploy.server.ServerService;
import com.ops.deploy.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantToolExecutor} 资源分配工具单测（grant 盲区修复）。
 *
 * <p>覆盖：add/list/remove_server_grant、add/list/remove_database_grant 的分发与参数映射
 * （serverId/databaseId/userId 正确落到 service 调用），以及非 ADMIN 角色 403 拦截。
 */
@ExtendWith(MockitoExtension.class)
class AssistantToolExecutorTest {

    @Mock
    private DashboardService dashboardService;
    @Mock
    private ServerService serverService;
    @Mock
    private DatabaseService databaseService;
    @Mock
    private ProjectService projectService;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private ServiceInstanceService instanceService;
    @Mock
    private UserService userService;
    @Mock
    private ProjectAccessService accessService;
    @Mock
    private ResourceAccessService resourceAccess;
    @Mock
    private KnowledgeBaseSearcher knowledgeBase;

    private AssistantToolExecutor executor;

    private static final AuthUser ADMIN = new AuthUser(1L, "admin", "ADMIN");
    private static final AuthUser OPS = new AuthUser(2L, "ops", "OPS");

    @BeforeEach
    void setUp() {
        executor = new AssistantToolExecutor(dashboardService, serverService, databaseService,
                projectService, deploymentService, instanceService, userService,
                accessService, resourceAccess, knowledgeBase);
        org.mockito.Mockito.lenient().when(accessService.isAdmin(ADMIN)).thenReturn(true);
        org.mockito.Mockito.lenient().when(accessService.isAdmin(OPS)).thenReturn(false);
    }

    @Test
    @DisplayName("add_server_grant: serverId+userId+operator 正确分发到 resourceAccess.addServerGrant")
    void addServerGrant_dispatch() {
        when(resourceAccess.addServerGrant(eq(3L), eq(11L), eq(ADMIN))).thenReturn(grantVO(3L, 11L));
        Object out = executor.dispatch("add_server_grant",
                Map.of("serverId", 3L, "userId", 11L), ADMIN);
        assertThat(out).isInstanceOf(ResourceGrantVO.class);
        verify(resourceAccess).addServerGrant(eq(3L), eq(11L), eq(ADMIN));
    }

    @Test
    @DisplayName("list_server_grants: 返回分配列表")
    void listServerGrants_dispatch() {
        when(resourceAccess.listServerGrants(3L)).thenReturn(List.of(grantVO(3L, 11L)));
        @SuppressWarnings("unchecked")
        List<ResourceGrantVO> out = (List<ResourceGrantVO>) executor.dispatch(
                "list_server_grants", Map.of("serverId", 3L), ADMIN);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getUserId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("remove_server_grant: 调 removeServerGrant 并返回文案")
    void removeServerGrant_dispatch() {
        Object out = executor.dispatch("remove_server_grant",
                Map.of("serverId", 3L, "userId", 11L), ADMIN);
        assertThat(out).isInstanceOf(String.class)
                .asString().contains("3").contains("11");
        verify(resourceAccess).removeServerGrant(3L, 11L, ADMIN);
    }

    @Test
    @DisplayName("add_database_grant / list_database_grants: 正确分发")
    void databaseGrant_dispatch() {
        when(resourceAccess.addDatabaseGrant(eq(5L), eq(11L), eq(ADMIN))).thenReturn(grantVO(5L, 11L));
        assertThat(executor.dispatch("add_database_grant",
                Map.of("databaseId", 5L, "userId", 11L), ADMIN)).isInstanceOf(ResourceGrantVO.class);
        when(resourceAccess.listDatabaseGrants(5L)).thenReturn(List.of());
        assertThat(executor.dispatch("list_database_grants", Map.of("databaseId", 5L), ADMIN))
                .isInstanceOf(List.class);
    }

    @Test
    @DisplayName("remove_database_grant: 调 removeDatabaseGrant")
    void removeDatabaseGrant_dispatch() {
        Object out = executor.dispatch("remove_database_grant",
                Map.of("databaseId", 5L, "userId", 11L), ADMIN);
        assertThat(out).isInstanceOf(String.class).asString().contains("5").contains("11");
        verify(resourceAccess).removeDatabaseGrant(5L, 11L, ADMIN);
    }

    @Test
    @DisplayName("grant 工具对非 ADMIN 角色抛 403（外部 RBAC 拦截）")
    void grantTools_nonAdmin_forbidden() {
        assertThatThrownBy(() -> executor.dispatch("add_server_grant",
                Map.of("serverId", 3L, "userId", 11L), OPS))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> executor.dispatch("remove_database_grant",
                Map.of("databaseId", 5L, "userId", 11L), OPS))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    private static ResourceGrantVO grantVO(long resourceId, long userId) {
        ResourceGrantVO vo = new ResourceGrantVO();
        vo.setResourceId(resourceId);
        vo.setUserId(userId);
        vo.setUsername("u" + userId);
        return vo;
    }
}
