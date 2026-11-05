package com.ops.deploy.security;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.database.DatabaseEntity;
import com.ops.deploy.database.DatabaseGrantRepository;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerGrantEntity;
import com.ops.deploy.server.ServerGrantId;
import com.ops.deploy.server.ServerGrantRepository;
import com.ops.deploy.user.Role;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResourceAccessService 单测：服务器/数据库可见性隔离核心逻辑（OPS 创建 ∪ 被分配）。
 *
 * <p>覆盖：ADMIN 不过滤（null）/ OPS 创建者全 OPS 可见 / 被分配可见 / 存量资源（createdBy=null）
 * 对 OPS 不可见 / 不可见断言 404 / 非 OPS 管理断言 403 / 分配增删（ADMIN 限定 + 冲突/404）。
 */
@ExtendWith(MockitoExtension.class)
class ResourceAccessServiceTest {

    @Mock
    private ServerGrantRepository serverGrantRepository;

    @Mock
    private DatabaseGrantRepository databaseGrantRepository;

    @Mock
    private UserRepository userRepository;

    private ResourceAccessService service;

    private static final AuthUser ADMIN = new AuthUser(1L, "admin", Role.ADMIN.name());
    private static final AuthUser OPS_A = new AuthUser(2L, "ops-a", Role.OPS.name());
    private static final AuthUser OPS_B = new AuthUser(3L, "ops-b", Role.OPS.name());

    private void setUp() {
        service = new ResourceAccessService(serverGrantRepository, databaseGrantRepository, userRepository);
    }

    private UserEntity user(Long id, String role) {
        UserEntity u = new UserEntity("u" + id, "hash", role);
        u.setId(id);
        return u;
    }

    private ServerEntity server(Long id, Long createdBy) {
        ServerEntity s = new ServerEntity();
        s.setId(id);
        s.setCreatedBy(createdBy);
        return s;
    }

    // ---------- 能力判定 ----------

    @Test
    void isAdmin_andIsOpsOrAbove() {
        setUp();
        assertThat(service.isAdmin(ADMIN)).isTrue();
        assertThat(service.isAdmin(OPS_A)).isFalse();
        assertThat(service.isOpsOrAbove(ADMIN)).isTrue();
        assertThat(service.isOpsOrAbove(OPS_A)).isTrue();
        assertThat(service.isOpsOrAbove(null)).isFalse();
    }

    @Test
    void isOpsCreated_adminCreatedOrLegacy_shouldBeFalse() {
        setUp();
        // 存量资源（createdBy = null）对 OPS 不可见
        assertThat(service.isOpsCreated(null)).isFalse();
        // ADMIN 创建 → 仅 ADMIN 可见，不进入 OPS 集合
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.ADMIN.name())));
        assertThat(service.isOpsCreated(1L)).isFalse();
    }

    @Test
    void isOpsCreated_opsCreated_shouldBeTrue() {
        setUp();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Role.OPS.name())));
        assertThat(service.isOpsCreated(2L)).isTrue();
    }

    // ---------- 可见范围（visibleServerIds） ----------

    @Test
    void visibleServerIds_admin_shouldReturnNullNoFilter() {
        setUp();
        List<ServerEntity> all = List.of(server(1L, 2L), server(2L, null));
        // ADMIN 直接返回 null（不过滤），且不触发任何 grant/user 查询
        assertThat(service.visibleServerIds(ADMIN, all)).isNull();
        verify(serverGrantRepository, never()).findServerIdByUserId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void visibleServerIds_ops_shouldBeOpsCreatedUnionGrants() {
        setUp();
        // s1 由 OPS_A 创建（对全体 OPS 可见），s2 为存量（createdBy=null，不可见），
        // s3 为 ADMIN 创建（不可见），s4 通过分配授予当前用户
        List<ServerEntity> all = List.of(server(1L, 2L), server(2L, null), server(3L, 1L), server(4L, 1L));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Role.OPS.name())));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.ADMIN.name())));
        when(serverGrantRepository.findServerIdByUserId(2L)).thenReturn(List.of(4L));

        List<Long> visible = service.visibleServerIds(OPS_A, all);

        assertThat(visible).containsExactly(1L, 4L);
    }

    @Test
    void visibleServerIds_nullUser_shouldReturnEmpty() {
        setUp();
        assertThat(service.visibleServerIds(null, List.of(server(1L, 2L)))).isEmpty();
    }

    // ---------- 可见性断言 ----------

    @Test
    void assertViewServer_visible_shouldPass() {
        setUp();
        // 被分配 → 可见
        when(serverGrantRepository.existsByServerIdAndUserId(1L, 2L)).thenReturn(true);
        service.assertViewServer(1L, server(1L, 1L), OPS_A); // 不抛异常
    }

    @Test
    void assertViewServer_invisibleOpsCreatedByOther_should404() {
        setUp();
        // s1 由 ADMIN 创建（非 OPS），且未分配给 OPS_A → 对 OPS_A 不可见 → 404
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.ADMIN.name())));
        when(serverGrantRepository.existsByServerIdAndUserId(1L, 2L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertViewServer(1L, server(1L, 1L), OPS_A))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void assertViewServer_admin_shouldAlwaysPass() {
        setUp();
        // ADMIN 直接可见，不触发任何查询
        service.assertViewServer(1L, server(1L, null), ADMIN);
    }

    @Test
    void assertManageServer_nullUser_should404() {
        setUp();
        // 当前 user 为 null → assertView 先 404，不触发任何用户查询
        assertThatThrownBy(() -> service.assertManageServer(1L, server(1L, 2L), null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- 分配管理 ----------

    @Test
    void addServerGrant_admin_shouldPersistAndReturnVo() {
        setUp();
        UserEntity target = user(2L, Role.OPS.name());
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(serverGrantRepository.existsByServerIdAndUserId(1L, 2L)).thenReturn(false);

        ResourceGrantVO vo = service.addServerGrant(1L, 2L, ADMIN);

        assertThat(vo.getResourceId()).isEqualTo(1L);
        assertThat(vo.getUserId()).isEqualTo(2L);
        assertThat(vo.getUsername()).isEqualTo("u2");
        assertThat(vo.getUserRole()).isEqualTo(Role.OPS.name());
        assertThat(vo.getResourceType()).isEqualTo("SERVER");
        verify(serverGrantRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addServerGrant_alreadyGranted_shouldConflict() {
        setUp();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Role.OPS.name())));
        when(serverGrantRepository.existsByServerIdAndUserId(1L, 2L)).thenReturn(true);

        assertThatThrownBy(() -> service.addServerGrant(1L, 2L, ADMIN))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void addServerGrant_opsOperator_shouldForbidden() {
        setUp();
        assertThatThrownBy(() -> service.addServerGrant(1L, 2L, OPS_A))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void addServerGrant_unknownTargetUser_should404() {
        setUp();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addServerGrant(1L, 99L, ADMIN))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void removeServerGrant_notExists_should404() {
        setUp();
        when(serverGrantRepository.findById(new ServerGrantId(1L, 2L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeServerGrant(1L, 2L, ADMIN))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void removeServerGrant_opsOperator_shouldForbidden() {
        setUp();
        assertThatThrownBy(() -> service.removeServerGrant(1L, 2L, OPS_A))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ---------- 数据库侧（与服务器侧对称，抽测可见性 + 分配） ----------

    private DatabaseEntity database(Long id, Long createdBy) {
        DatabaseEntity d = new DatabaseEntity();
        d.setId(id);
        d.setCreatedBy(createdBy);
        return d;
    }

    @Test
    void visibleDatabaseIds_ops_shouldBeOpsCreatedUnionGrants() {
        setUp();
        List<DatabaseEntity> all = List.of(database(1L, 2L), database(2L, null), database(3L, 1L));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Role.OPS.name())));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.ADMIN.name())));
        when(databaseGrantRepository.findDatabaseIdByUserId(3L)).thenReturn(List.of(3L));

        List<Long> visible = service.visibleDatabaseIds(OPS_B, all);

        // d1 由 OPS 创建 → 可见；d2 存量 → 不可见；d3 分配给 OPS_B → 可见
        assertThat(visible).containsExactly(1L, 3L);
    }

    @Test
    void addDatabaseGrant_admin_shouldReturnVo() {
        setUp();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Role.OPS.name())));
        when(databaseGrantRepository.existsByDatabaseIdAndUserId(1L, 2L)).thenReturn(false);

        ResourceGrantVO vo = service.addDatabaseGrant(1L, 2L, ADMIN);

        assertThat(vo.getResourceType()).isEqualTo("DATABASE");
        assertThat(vo.getResourceId()).isEqualTo(1L);
        assertThat(vo.getUsername()).isEqualTo("u2");
        verify(databaseGrantRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listServerGrants_shouldMapToVo() {
        setUp();
        ServerGrantEntity g = new ServerGrantEntity();
        g.setServerId(1L);
        g.setUserId(2L);
        g.setGrantedAt(LocalDateTime.now());
        when(serverGrantRepository.findByServerIdOrderByGrantedAtAsc(1L)).thenReturn(List.of(g));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Role.OPS.name())));

        List<ResourceGrantVO> vos = service.listServerGrants(1L);

        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getUserId()).isEqualTo(2L);
        assertThat(vos.get(0).getUsername()).isEqualTo("u2");
    }
}
