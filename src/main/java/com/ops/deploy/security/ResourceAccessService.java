package com.ops.deploy.security;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.database.DatabaseEntity;
import com.ops.deploy.database.DatabaseGrantEntity;
import com.ops.deploy.database.DatabaseGrantId;
import com.ops.deploy.database.DatabaseGrantRepository;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerGrantEntity;
import com.ops.deploy.server.ServerGrantId;
import com.ops.deploy.server.ServerGrantRepository;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 服务器/数据库资源访问控制（资源隔离 + 按用户分配）。
 *
 * <p>语义 = <b>全局角色决定写能力上限，分配关系 + 创建人决定可见范围</b>：
 * <ul>
 *   <li>ADMIN（全局）：可见/可完全管理全部服务器与数据库，并可分配/取消分配资源给 OPS；</li>
 *   <li>OPS：可见 = (任一 OPS 创建的资源) ∪ (被管理员分配给自己的资源)；
 *       对可见资源可完全管理（编辑/删除/连通性测试），也可自建资源（自建资源对全体 OPS 可见）；</li>
 *   <li>不可见：资源对其不存在（严格隔离，统一 404，不泄露资源存在性，防枚举）。</li>
 * </ul>
 *
 * <p>存量资源（V13 之前创建，created_by 为空）不归属任何 OPS，须由管理员显式分配后对 OPS 可见。
 */
@Service
public class ResourceAccessService {

    /** 资源类型（分配列表展示 + 审计用）。 */
    public enum ResourceType { SERVER, DATABASE }

    private static final Set<String> OPS_ROLE = Set.of("OPS");
    private static final Set<String> ADMIN_ROLE = Set.of("ADMIN");

    /** 角色归一化：去空白 + 大写，避免 DB/JWT 中大小写或空白不一致导致判断失效。 */
    private static String normalizeRole(String role) {
        return role == null ? null : role.trim().toUpperCase();
    }

    private final ServerGrantRepository serverGrantRepository;
    private final DatabaseGrantRepository databaseGrantRepository;
    private final UserRepository userRepository;

    public ResourceAccessService(ServerGrantRepository serverGrantRepository,
                                 DatabaseGrantRepository databaseGrantRepository,
                                 UserRepository userRepository) {
        this.serverGrantRepository = serverGrantRepository;
        this.databaseGrantRepository = databaseGrantRepository;
        this.userRepository = userRepository;
    }

    // ---------- 能力判定 ----------

    public boolean isAdmin(AuthUser user) {
        return user != null && ADMIN_ROLE.contains(normalizeRole(user.getRole()));
    }

    /** 全局角色 ≥ OPS（具备平台资源写能力）。 */
    public boolean isOpsOrAbove(AuthUser user) {
        return isAdmin(user) || (user != null && OPS_ROLE.contains(normalizeRole(user.getRole())));
    }

    /** 该创建人是否 OPS（OPS 创建的资源对全体 OPS 可见）。 */
    public boolean isOpsCreated(Long createdBy) {
        if (createdBy == null) {
            return false;
        }
        return userRepository.findById(createdBy)
                .map(u -> OPS_ROLE.contains(normalizeRole(u.getRole())))
                .orElse(false);
    }

    // ---------- 可见范围（null = 不过滤，ADMIN 专用；空列表 = 直接返回空结果） ----------

    /** 当前用户可见的服务器 id 集合（OPS 创建 ∪ 被分配）。 */
    public List<Long> visibleServerIds(AuthUser user, List<ServerEntity> all) {
        if (isAdmin(user)) {
            return null;
        }
        if (user == null) {
            return List.of();
        }
        Set<Long> visible = new TreeSet<>();
        for (ServerEntity s : all) {
            if (isOpsCreated(s.getCreatedBy())) {
                visible.add(s.getId());
            }
        }
        visible.addAll(serverGrantRepository.findServerIdByUserId(user.getUserId()));
        return visible.stream().collect(Collectors.toList());
    }

    /** 当前用户可见的数据库 id 集合（OPS 创建 ∪ 被分配）。 */
    public List<Long> visibleDatabaseIds(AuthUser user, List<DatabaseEntity> all) {
        if (isAdmin(user)) {
            return null;
        }
        if (user == null) {
            return List.of();
        }
        Set<Long> visible = new TreeSet<>();
        for (DatabaseEntity d : all) {
            if (isOpsCreated(d.getCreatedBy())) {
                visible.add(d.getId());
            }
        }
        visible.addAll(databaseGrantRepository.findDatabaseIdByUserId(user.getUserId()));
        return visible.stream().collect(Collectors.toList());
    }

    // ---------- 断言（统一 404：不可见视同不存在，防枚举） ----------

    /** 可见性断言（读）：不存在或不可见均 → 404。 */
    public void assertViewServer(Long serverId, ServerEntity entity, AuthUser user) {
        if (serverId == null || entity == null || !canViewServer(serverId, entity, user)) {
            throw new BizException(ErrorCode.NOT_FOUND, "server not found");
        }
    }

    public void assertViewDatabase(Long databaseId, DatabaseEntity entity, AuthUser user) {
        if (databaseId == null || entity == null || !canViewDatabase(databaseId, entity, user)) {
            throw new BizException(ErrorCode.NOT_FOUND, "database not found");
        }
    }

    /** 完全管理断言（编辑/删除/测试）：可见 且 全局 &ge; OPS。
     *  平台仅存 ADMIN/OPS 两种角色，可见即可管理；独立保留以显式表达意图。 */
    public void assertManageServer(Long serverId, ServerEntity entity, AuthUser user) {
        assertViewServer(serverId, entity, user);
        requireOpsOrAbove(user);
    }

    public void assertManageDatabase(Long databaseId, DatabaseEntity entity, AuthUser user) {
        assertViewDatabase(databaseId, entity, user);
        requireOpsOrAbove(user);
    }

    private boolean canViewServer(Long serverId, ServerEntity entity, AuthUser user) {
        if (isAdmin(user)) {
            return true;
        }
        if (user == null) {
            return false;
        }
        return isOpsCreated(entity.getCreatedBy())
                || serverGrantRepository.existsByServerIdAndUserId(serverId, user.getUserId());
    }

    private boolean canViewDatabase(Long databaseId, DatabaseEntity entity, AuthUser user) {
        if (isAdmin(user)) {
            return true;
        }
        if (user == null) {
            return false;
        }
        return isOpsCreated(entity.getCreatedBy())
                || databaseGrantRepository.existsByDatabaseIdAndUserId(databaseId, user.getUserId());
    }

    private void requireOpsOrAbove(AuthUser user) {
        if (!isOpsOrAbove(user)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "insufficient role for this operation (OPS or above required)");
        }
    }

    // ---------- 分配管理（仅 ADMIN，Controller 层 @RequireRole(ADMIN) 兜底） ----------

    public List<ResourceGrantVO> listServerGrants(Long serverId) {
        List<ResourceGrantVO> vos = new ArrayList<>();
        for (ServerGrantEntity g : serverGrantRepository.findByServerIdOrderByGrantedAtAsc(serverId)) {
            vos.add(toVO(ResourceType.SERVER, serverId, g.getUserId(), g.getGrantedAt()));
        }
        return vos;
    }

    public List<ResourceGrantVO> listDatabaseGrants(Long databaseId) {
        List<ResourceGrantVO> vos = new ArrayList<>();
        for (DatabaseGrantEntity g : databaseGrantRepository.findByDatabaseIdOrderByGrantedAtAsc(databaseId)) {
            vos.add(toVO(ResourceType.DATABASE, databaseId, g.getUserId(), g.getGrantedAt()));
        }
        return vos;
    }

    @Transactional
    public ResourceGrantVO addServerGrant(Long serverId, Long userId, AuthUser operator) {
        if (!isAdmin(operator)) {
            throw new BizException(ErrorCode.FORBIDDEN, "only ADMIN can grant servers");
        }
        UserEntity target = requireUser(userId);
        if (serverGrantRepository.existsByServerIdAndUserId(serverId, userId)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user " + target.getUsername() + " already has access to this server");
        }
        ServerGrantEntity g = new ServerGrantEntity();
        g.setServerId(serverId);
        g.setUserId(userId);
        g.setGrantedAt(LocalDateTime.now());
        serverGrantRepository.save(g);
        return toVO(ResourceType.SERVER, serverId, userId, g.getGrantedAt());
    }

    @Transactional
    public void removeServerGrant(Long serverId, Long userId, AuthUser operator) {
        if (!isAdmin(operator)) {
            throw new BizException(ErrorCode.FORBIDDEN, "only ADMIN can revoke server grants");
        }
        serverGrantRepository.findById(new ServerGrantId(serverId, userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "grant not found"));
        serverGrantRepository.deleteById(new ServerGrantId(serverId, userId));
    }

    @Transactional
    public ResourceGrantVO addDatabaseGrant(Long databaseId, Long userId, AuthUser operator) {
        if (!isAdmin(operator)) {
            throw new BizException(ErrorCode.FORBIDDEN, "only ADMIN can grant databases");
        }
        UserEntity target = requireUser(userId);
        if (databaseGrantRepository.existsByDatabaseIdAndUserId(databaseId, userId)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user " + target.getUsername() + " already has access to this database");
        }
        DatabaseGrantEntity g = new DatabaseGrantEntity();
        g.setDatabaseId(databaseId);
        g.setUserId(userId);
        g.setGrantedAt(LocalDateTime.now());
        databaseGrantRepository.save(g);
        return toVO(ResourceType.DATABASE, databaseId, userId, g.getGrantedAt());
    }

    @Transactional
    public void removeDatabaseGrant(Long databaseId, Long userId, AuthUser operator) {
        if (!isAdmin(operator)) {
            throw new BizException(ErrorCode.FORBIDDEN, "only ADMIN can revoke database grants");
        }
        databaseGrantRepository.findById(new DatabaseGrantId(databaseId, userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "grant not found"));
        databaseGrantRepository.deleteById(new DatabaseGrantId(databaseId, userId));
    }

    private UserEntity requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "user not found: " + userId));
    }

    private ResourceGrantVO toVO(ResourceType type, Long resourceId, Long userId, LocalDateTime grantedAt) {
        UserEntity u = userRepository.findById(userId).orElse(null);
        ResourceGrantVO vo = new ResourceGrantVO();
        vo.setResourceType(type.name());
        vo.setResourceId(resourceId);
        vo.setUserId(userId);
        vo.setUsername(u != null ? u.getUsername() : "user-" + userId);
        vo.setUserRole(u != null ? u.getRole() : null);
        vo.setGrantedAt(grantedAt);
        return vo;
    }
}
