package com.ops.deploy.aiassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.dashboard.DashboardService;
import com.ops.deploy.database.DatabaseCreateRequest;
import com.ops.deploy.database.DatabaseService;
import com.ops.deploy.database.DatabaseUpdateRequest;
import com.ops.deploy.deploy.DeploymentCreateRequest;
import com.ops.deploy.deploy.DeploymentService;
import com.ops.deploy.deploy.ServiceInstanceService;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectCreateRequest;
import com.ops.deploy.project.ProjectService;
import com.ops.deploy.project.ProjectUpdateRequest;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.ResourceAccessService;
import com.ops.deploy.server.ServerCreateRequest;
import com.ops.deploy.server.ServerService;
import com.ops.deploy.server.ServerUpdateRequest;
import com.ops.deploy.user.Role;
import com.ops.deploy.user.UserCreateRequest;
import com.ops.deploy.user.UserService;
import com.ops.deploy.user.UserUpdateRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 助手工具执行器：把 LLM 发起的具名工具调用映射到既有 Service。
 *
 * <p><b>权限收敛</b>：ReAct 循环不感知业务权限，全部由本类把守。
 * 本 codebase 约定"带 {@code AuthUser} 的 Service 方法内部已做权限收敛"
 * （各 Controller 均依赖此约定），故 executor 仅在 Service 方法<b>不带 user</b>
 * 的写操作上补外部校验：
 * <ul>
 *   <li>{@code server/database create}：create 只收 {@code createdBy}（无角色上下文）→ 外部要求 OPS 及以上；</li>
 *   <li>用户管理（{@code UserService.*}）：方法不带 user（由 Controller 的 @RequireRole 把守）→ 外部要求 ADMIN。</li>
 * </ul>
 * 越权时抛 {@link BizException}（FORBIDDEN/BAD_REQUEST），由 ReAct 循环捕获后
 * 作为工具错误回传 LLM，让模型向用户说明，而非静默放行。
 *
 * <p>单入口 {@link #dispatch(String, Map, AuthUser)} 返回可 JSON 序列化对象
 * （VO / record / Map），由 {@link AssistantReActService} 统一序列化回传。
 */
@Component
public class AssistantToolExecutor {

    private final DashboardService dashboardService;
    private final ServerService serverService;
    private final DatabaseService databaseService;
    private final ProjectService projectService;
    private final DeploymentService deploymentService;
    private final ServiceInstanceService instanceService;
    private final UserService userService;
    private final ProjectAccessService accessService;
    private final ResourceAccessService resourceAccess;
    private final KnowledgeBaseSearcher knowledgeBase;

    /** 保留 ObjectMapper 以便未来扩展（当前 dispatch 返回类型均为可直接序列化对象）。 */
    @SuppressWarnings("unused")
    private final ObjectMapper json = new ObjectMapper();

    public AssistantToolExecutor(DashboardService dashboardService,
                                 ServerService serverService,
                                 DatabaseService databaseService,
                                 ProjectService projectService,
                                 DeploymentService deploymentService,
                                 ServiceInstanceService instanceService,
                                 UserService userService,
                                 ProjectAccessService accessService,
                                 ResourceAccessService resourceAccess,
                                 KnowledgeBaseSearcher knowledgeBase) {
        this.dashboardService = dashboardService;
        this.serverService = serverService;
        this.databaseService = databaseService;
        this.projectService = projectService;
        this.deploymentService = deploymentService;
        this.instanceService = instanceService;
        this.userService = userService;
        this.accessService = accessService;
        this.resourceAccess = resourceAccess;
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 路由并执行工具。
     *
     * @param tool 工具名（见 {@link AssistantReActService#toolDefs()}）
     * @param args LLM 传入的 JSON 参数（已反序列化为 Map，可能为 null）
     * @param user 当前登录用户
     * @return 可 JSON 序列化结果对象
     * @throws BizException 参数缺失/非法（BAD_REQUEST）或越权（FORBIDDEN）
     */
    public Object dispatch(String tool, Map<String, Object> args, AuthUser user) {
        if (tool == null || tool.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工具名为空");
        }
        return switch (tool) {
            // ---- 仪表盘 ----
            case "get_dashboard" -> dashboardService.stats(user);

            // ---- 服务器（全局资源）----
            case "list_servers" -> serverService.listFor(user);
            case "get_server" -> serverService.viewFor(longArg(args, "id"), user);
            case "create_server" -> { requireOps(user); yield serverService.create(toServerCreate(args), user.getUserId()); }
            case "update_server" -> serverService.updateFor(longArg(args, "id"), toServerUpdate(args), user);
            case "delete_server" -> { long id = longArg(args, "id"); serverService.deleteFor(id, user); yield "已删除服务器 id=" + id; }

            // ---- 数据库（全局资源）----
            case "list_databases" -> databaseService.listFor(user);
            case "get_database" -> databaseService.viewFor(longArg(args, "id"), user);
            case "create_database" -> { requireOps(user); yield databaseService.create(toDatabaseCreate(args), user.getUserId()); }
            case "update_database" -> databaseService.updateFor(longArg(args, "id"), toDatabaseUpdate(args), user);
            case "delete_database" -> { long id = longArg(args, "id"); databaseService.deleteFor(id, user); yield "已删除数据库 id=" + id; }

            // ---- 项目 ----
            case "list_projects" -> projectService.list(user);
            case "get_project" -> projectService.get(longArg(args, "id"), user);
            case "create_project" -> projectService.create(toProjectCreate(args), user);
            case "update_project" -> projectService.update(longArg(args, "id"), toProjectUpdate(args), user);
            case "delete_project" -> { long id = longArg(args, "id"); projectService.delete(id, user); yield "已删除项目 id=" + id; }
            case "list_project_versions" -> projectService.listVersions(longArg(args, "projectId"), user);

            // ---- 部署任务 ----
            case "list_deployments" -> deploymentService.list(user);
            case "get_deployment" -> deploymentService.get(longArg(args, "id"), user);
            case "get_deployment_steps" -> deploymentService.getSteps(longArg(args, "id"), user);
            case "create_deployment" -> deploymentService.create(toDeploymentCreate(args), user);
            case "cancel_deployment" -> deploymentService.cancel(longArg(args, "id"), user);
            case "redeploy" -> deploymentService.redeploy(longArg(args, "id"), user);
            case "rollback_deployment" -> deploymentService.rollback(longArg(args, "id"), user);

            // ---- 服务实例 ----
            case "list_services" -> instanceService.list(longOrNull(args, "projectId"), longOrNull(args, "serverId"), longOrNull(args, "serviceId"), user);
            case "get_service" -> instanceService.get(longArg(args, "id"), user);
            case "start_service" -> instanceService.start(longArg(args, "id"), user);
            case "stop_service" -> instanceService.stop(longArg(args, "id"), user);
            case "restart_service" -> instanceService.restart(longArg(args, "id"), user);
            case "delete_service" -> { long id = longArg(args, "id"); instanceService.delete(id, user); yield "已删除服务 id=" + id; }

            // ---- 用户（ADMIN only）----
            case "list_users" -> { requireAdmin(user); yield userService.list(); }
            case "get_user" -> { requireAdmin(user); yield userService.get(longArg(args, "id")); }
            case "create_user" -> { requireAdmin(user); yield userService.create(toUserCreate(args)); }
            case "update_user" -> { requireAdmin(user); yield userService.update(longArg(args, "id"), toUserUpdate(args)); }
            case "delete_user" -> { requireAdmin(user); long id = longArg(args, "id"); userService.delete(id); yield "已删除用户 id=" + id; }

            // ---- 资源分配（服务器/数据库按用户分配，ADMIN only）----
            // 分配粒度是"单台服务器/单个数据库 → 单个用户"（server_grant/db_grant），
            // 与项目关联（update_user 的 projectIds）正交：被分配用户获得该资源的 OPS 级管理权。
            // service 方法带 AuthUser operator（审计），RBAC 由 requireAdmin 在调用前强制。
            case "list_server_grants" -> {
                requireAdmin(user);
                yield resourceAccess.listServerGrants(longArg(args, "serverId"));
            }
            case "add_server_grant" -> {
                requireAdmin(user);
                yield resourceAccess.addServerGrant(longArg(args, "serverId"), longArg(args, "userId"), user);
            }
            case "remove_server_grant" -> {
                requireAdmin(user);
                long serverId = longArg(args, "serverId");
                resourceAccess.removeServerGrant(serverId, longArg(args, "userId"), user);
                yield "已移除服务器 id=" + serverId + " 对用户 id=" + longArg(args, "userId") + " 的分配";
            }
            case "list_database_grants" -> {
                requireAdmin(user);
                yield resourceAccess.listDatabaseGrants(longArg(args, "databaseId"));
            }
            case "add_database_grant" -> {
                requireAdmin(user);
                yield resourceAccess.addDatabaseGrant(longArg(args, "databaseId"), longArg(args, "userId"), user);
            }
            case "remove_database_grant" -> {
                requireAdmin(user);
                long databaseId = longArg(args, "databaseId");
                resourceAccess.removeDatabaseGrant(databaseId, longArg(args, "userId"), user);
                yield "已移除数据库 id=" + databaseId + " 对用户 id=" + longArg(args, "userId") + " 的分配";
            }

            // ---- 知识库（成功部署文档问答）----
            case "search_docs" -> knowledgeBase.search(strArg(args, "keyword"), intArg(args, "limit", 3), user);

            default -> throw new BizException(ErrorCode.BAD_REQUEST, "未知工具: " + tool);
        };
    }

    // ---------- 外部权限把守（仅 Service 方法不带 user 时）----------

    private void requireAdmin(AuthUser user) {
        if (!accessService.isAdmin(user)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可管理用户");
        }
    }

    private void requireOps(AuthUser user) {
        if (!accessService.isOpsOrAbove(user)) {
            throw new BizException(ErrorCode.FORBIDDEN, "该操作需 OPS 及以上角色");
        }
    }

    // ---------- 参数 → DTO ----------

    private ServerCreateRequest toServerCreate(Map<String, Object> a) {
        ServerCreateRequest r = new ServerCreateRequest();
        r.setName(strArg(a, "name"));
        r.setIp(strArg(a, "ip"));
        r.setSshPort(intArg(a, "sshPort", 22));
        r.setSshUser(strArg(a, "sshUser"));
        r.setAuthType(strOrNull(a, "authType"));
        r.setPassword(strArg(a, "password"));
        r.setEnvTag(strOrNull(a, "envTag"));
        return r;
    }

    private ServerUpdateRequest toServerUpdate(Map<String, Object> a) {
        ServerUpdateRequest r = new ServerUpdateRequest();
        r.setName(strOrNull(a, "name"));
        r.setIp(strOrNull(a, "ip"));
        r.setSshPort(intOrNull(a, "sshPort"));
        r.setSshUser(strOrNull(a, "sshUser"));
        r.setAuthType(strOrNull(a, "authType"));
        r.setPassword(strOrNull(a, "password"));
        r.setEnvTag(strOrNull(a, "envTag"));
        return r;
    }

    private DatabaseCreateRequest toDatabaseCreate(Map<String, Object> a) {
        DatabaseCreateRequest r = new DatabaseCreateRequest();
        r.setName(strArg(a, "name"));
        r.setDbType(strArg(a, "dbType"));
        r.setHost(strArg(a, "host"));
        r.setPort(intArg(a, "port", 5432));
        r.setDatabaseName(strArg(a, "databaseName"));
        r.setUsername(strArg(a, "username"));
        r.setPassword(strArg(a, "password"));
        return r;
    }

    private DatabaseUpdateRequest toDatabaseUpdate(Map<String, Object> a) {
        DatabaseUpdateRequest r = new DatabaseUpdateRequest();
        r.setName(strOrNull(a, "name"));
        r.setDbType(strOrNull(a, "dbType"));
        r.setHost(strOrNull(a, "host"));
        r.setPort(intOrNull(a, "port"));
        r.setDatabaseName(strOrNull(a, "databaseName"));
        r.setUsername(strOrNull(a, "username"));
        r.setPassword(strOrNull(a, "password"));
        return r;
    }

    private ProjectCreateRequest toProjectCreate(Map<String, Object> a) {
        ProjectCreateRequest r = new ProjectCreateRequest();
        r.setName(strArg(a, "name"));
        r.setDescription(strOrNull(a, "description"));
        r.setDefaultPort(intOrNull(a, "defaultPort"));
        return r;
    }

    private ProjectUpdateRequest toProjectUpdate(Map<String, Object> a) {
        ProjectUpdateRequest r = new ProjectUpdateRequest();
        r.setDescription(strOrNull(a, "description"));
        r.setDetectedType(strOrNull(a, "detectedType"));
        r.setDefaultStartCmd(strOrNull(a, "defaultStartCmd"));
        r.setDefaultPort(intOrNull(a, "defaultPort"));
        r.setDefaultHealthPath(strOrNull(a, "defaultHealthPath"));
        return r;
    }

    private DeploymentCreateRequest toDeploymentCreate(Map<String, Object> a) {
        DeploymentCreateRequest r = new DeploymentCreateRequest();
        r.setVersionId(longArg(a, "versionId"));
        r.setServerId(longArg(a, "serverId"));
        r.setStartCmd(strOrNull(a, "startCmd"));
        r.setMode(strOrNull(a, "mode"));
        r.setPort(intOrNull(a, "port"));
        r.setHealthPath(strOrNull(a, "healthPath"));
        r.setDatabaseId(longOrNull(a, "databaseId"));
        return r;
    }

    private UserCreateRequest toUserCreate(Map<String, Object> a) {
        UserCreateRequest r = new UserCreateRequest();
        r.setUsername(strArg(a, "username"));
        r.setPassword(strArg(a, "password"));
        r.setRole(roleName(strArg(a, "role", "OPS")));
        return r;
    }

    private UserUpdateRequest toUserUpdate(Map<String, Object> a) {
        UserUpdateRequest r = new UserUpdateRequest();
        r.setRole(roleName(strOrNull(a, "role")));
        r.setPassword(strOrNull(a, "password"));
        r.setEnabled(boolOrNull(a, "enabled"));
        return r;
    }

    private String roleName(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(s.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色必须是 ADMIN 或 OPS: " + s);
        }
    }

    // ---------- 参数取值助手（缺失/非法 → BAD_REQUEST，回传 LLM 自我纠正）----------

    private static String strArg(Map<String, Object> a, String key) {
        return strArg(a, key, key);
    }

    private static String strArg(Map<String, Object> a, String key, String dflt) {
        String s = strOrNull(a, key);
        if (s == null) {
            if (dflt == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "缺少必填参数: " + key);
            }
            return dflt;
        }
        return s;
    }

    private static String strOrNull(Map<String, Object> a, String key) {
        if (a == null) {
            return null;
        }
        Object v = a.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static long longArg(Map<String, Object> a, String key) {
        String s = strOrNull(a, key);
        if (s == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少必填参数: " + key);
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数 " + key + " 不是合法数字: " + s);
        }
    }

    private static Long longOrNull(Map<String, Object> a, String key) {
        String s = strOrNull(a, key);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intArg(Map<String, Object> a, String key, int dflt) {
        String s = strOrNull(a, key);
        if (s == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Integer intOrNull(Map<String, Object> a, String key) {
        String s = strOrNull(a, key);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean boolOrNull(Map<String, Object> a, String key) {
        String s = strOrNull(a, key);
        return s == null ? null : Boolean.parseBoolean(s);
    }
}
