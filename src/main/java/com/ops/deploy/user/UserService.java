package com.ops.deploy.user;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.database.DatabaseGrantRepository;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.project.PackageVersionRepository;
import com.ops.deploy.project.ProjectMemberRepository;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.server.ServerGrantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理：CRUD + 角色分配。
 *
 * <p>安全要点：
 * - 密码只存 BCrypt hash，响应绝不回传；
 * - 用户名唯一（23505 竞态 → CONFLICT）；
 * - 最后一个启用中的 ADMIN 不可删除/禁用/降级，防止平台失去管理员；
 * - 删除前引用检查：部署操作人 / 包版本上传人 / 项目创建人 / 项目成员
 *   任一存在 → CONFLICT（避免破坏审计溯源与项目归属）。
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final DeploymentRepository deploymentRepository;
    private final PackageVersionRepository packageVersionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ServerGrantRepository serverGrantRepository;
    private final DatabaseGrantRepository databaseGrantRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserService(UserRepository userRepository,
                       DeploymentRepository deploymentRepository,
                       PackageVersionRepository packageVersionRepository,
                       ProjectRepository projectRepository,
                       ProjectMemberRepository projectMemberRepository,
                       ServerGrantRepository serverGrantRepository,
                       DatabaseGrantRepository databaseGrantRepository) {
        this(userRepository, deploymentRepository, packageVersionRepository,
                projectRepository, projectMemberRepository, serverGrantRepository,
                databaseGrantRepository, new BCryptPasswordEncoder());
    }

    /** 可注入自定义 PasswordEncoder（便于单测）。 */
    public UserService(UserRepository userRepository,
                       DeploymentRepository deploymentRepository,
                       PackageVersionRepository packageVersionRepository,
                       ProjectRepository projectRepository,
                       ProjectMemberRepository projectMemberRepository,
                       ServerGrantRepository serverGrantRepository,
                       DatabaseGrantRepository databaseGrantRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.deploymentRepository = deploymentRepository;
        this.packageVersionRepository = packageVersionRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.serverGrantRepository = serverGrantRepository;
        this.databaseGrantRepository = databaseGrantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserVO create(UserCreateRequest req) {
        Role role = parseRole(req.getRole());
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BizException(ErrorCode.CONFLICT, "username already exists");
        }
        UserEntity entity = new UserEntity(
                req.getUsername(), passwordEncoder.encode(req.getPassword()), role.name());
        try {
            userRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // 仅 (username) 唯一约束竞态（23505）→ CONFLICT；其它违规交全局处理器归类
            if (e.getMessage() != null && e.getMessage().contains("23505")) {
                throw new BizException(ErrorCode.CONFLICT, "username already exists");
            }
            throw e;
        }
        return UserVO.from(entity);
    }

    /** 全部用户（附已分配项目 id，一次全量分组避免 N+1）。 */
    public List<UserVO> list() {
        Map<Long, List<Long>> memberProjects =
                projectMemberRepository.findAll(Sort.by(Sort.Direction.ASC, "projectId"))
                        .stream()
                        .collect(Collectors.groupingBy(
                                m -> m.getUserId(),
                                Collectors.mapping(m -> m.getProjectId(), Collectors.toList())));
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(entity -> {
                    UserVO vo = UserVO.from(entity);
                    vo.setProjectIds(memberProjects.getOrDefault(entity.getId(), List.of()));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    public UserVO get(Long id) {
        return UserVO.from(require(id));
    }

    @Transactional
    public UserVO update(Long id, UserUpdateRequest req) {
        UserEntity entity = require(id);

        // 角色变更：白名单 + 最后一个启用中的 ADMIN 不可降级
        if (req.getRole() != null && !req.getRole().isEmpty()) {
            Role newRole = parseRole(req.getRole());
            if (Role.ADMIN.name().equals(entity.getRole())
                    && newRole != Role.ADMIN
                    && countEnabledAdmins() <= 1) {
                throw new BizException(ErrorCode.CONFLICT,
                        "cannot demote the last active ADMIN");
            }
            entity.setRole(newRole.name());
        }
        // 禁用：最后一个启用中的 ADMIN 不可禁用
        if (Boolean.FALSE.equals(req.getEnabled())
                && Role.ADMIN.name().equals(entity.getRole())
                && countEnabledAdmins() <= 1) {
            throw new BizException(ErrorCode.CONFLICT,
                    "cannot disable the last active ADMIN");
        }
        if (req.getEnabled() != null) {
            entity.setEnabled(req.getEnabled());
        }
        // 密码：null/空 → 保留原 hash；提供新值 → 重新 BCrypt
        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            entity.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }

        userRepository.save(entity);
        return UserVO.from(entity);
    }

    @Transactional
    public void delete(Long id) {
        UserEntity entity = require(id);
        // 最后一个启用中的 ADMIN 不可删除
        if (Role.ADMIN.name().equals(entity.getRole())
                && entity.isEnabled()
                && countEnabledAdmins() <= 1) {
            throw new BizException(ErrorCode.CONFLICT,
                    "cannot delete the last active ADMIN");
        }
        // 引用检查（外键均为 NO ACTION，直接删会抛 DataIntegrityViolationException 兜底成 500，
        // 这里先查引用给出明确 409 与原因）
        if (deploymentRepository.existsByOperator(id)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user is the operator of deployment records, cannot delete");
        }
        if (packageVersionRepository.existsByUploadedBy(id)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user has uploaded package versions, cannot delete");
        }
        if (projectRepository.existsByCreatedBy(id)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user created projects, cannot delete");
        }
        if (projectMemberRepository.existsByUserId(id)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user is a member of projects, remove from all projects first");
        }
        if (serverGrantRepository.existsByUserId(id)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user has server grants, revoke them first");
        }
        if (databaseGrantRepository.existsByUserId(id)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user has database grants, revoke them first");
        }
        userRepository.delete(entity);
    }

    /** 加载用户实体（不存在抛 404）。 */
    public UserEntity require(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "user not found"));
    }

    private long countEnabledAdmins() {
        return userRepository.countByRoleAndEnabled(Role.ADMIN.name(), true);
    }

    private static Role parseRole(String role) {
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid role: " + role);
        }
    }
}
