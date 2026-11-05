package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目访问控制（项目隔离 + 项目成员）：
 *
 * <p>语义 = <b>全局角色决定写能力上限，项目成员关系决定作用域</b>：
 * <ul>
 *   <li>ADMIN（全局）：可见/可操作/可管理全部项目，绕过成员校验；</li>
 *   <li>成员（项目内无角色分级，创建者与被分配者权限相同）：可见该项目及其部署/实例/版本，
 *       且全局角色 &ge; OPS 时即可完全管理（上传包/部署/启停实例/删项目/改配置/管理成员）；</li>
 *   <li>非成员：项目对其不存在（严格隔离，统一 404，不泄露项目存在性）。</li>
 * </ul>
 */
@Service
public class ProjectAccessService {

    private final ProjectMemberRepository memberRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ProjectAccessService(ProjectMemberRepository memberRepository,
                                ProjectRepository projectRepository,
                                UserRepository userRepository) {
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    // ---------- 能力判定 ----------

    public boolean isAdmin(AuthUser user) {
        return user != null && "ADMIN".equals(user.getRole());
    }

    /** 全局角色 &ge; OPS（具备平台写能力）。 */
    public boolean isOpsOrAbove(AuthUser user) {
        return isAdmin(user) || (user != null && "OPS".equals(user.getRole()));
    }

    public boolean isMember(Long projectId, Long userId) {
        return memberRepository.existsByProjectIdAndUserId(projectId, userId);
    }

    /** 可见（读）：ADMIN 或成员。 */
    public boolean canView(Long projectId, AuthUser user) {
        if (projectId == null || user == null) {
            return false;
        }
        return isAdmin(user) || isMember(projectId, user.getUserId());
    }

    /** 可操作（上传包/部署/启停实例/取消重跑等写操作）：可见 且 全局角色 &ge; OPS。 */
    public boolean canOperate(Long projectId, AuthUser user) {
        return canView(projectId, user) && isOpsOrAbove(user);
    }

    /** 可管理项目（删项目/改项目/删版本/改配置文件/管理成员）：ADMIN 或项目成员（且全局 &ge; OPS）。 */
    public boolean canManageProject(Long projectId, AuthUser user) {
        if (projectId == null || user == null || !isOpsOrAbove(user)) {
            return false;
        }
        return isAdmin(user) || isMember(projectId, user.getUserId());
    }

    /**
     * 当前用户可见的 projectId 集合；ADMIN 返回 null 表示不过滤（全部可见）。
     * 非成员集合为空时返回空列表（调用方直接返回空结果）。
     */
    public List<Long> visibleProjectIds(AuthUser user) {
        if (isAdmin(user)) {
            return null;
        }
        return memberRepository.findProjectIdByUserId(user.getUserId());
    }

    // ---------- 断言（统一 404：非成员视同不存在，防枚举） ----------

    public void assertView(Long projectId, AuthUser user) {
        requireProjectExists(projectId);
        if (!canView(projectId, user)) {
            throw new BizException(ErrorCode.NOT_FOUND, "project not found");
        }
    }

    public void assertOperate(Long projectId, AuthUser user) {
        requireProjectExists(projectId);
        if (!canOperate(projectId, user)) {
            // 存在但无操作权 → 403；不存在或非成员 → 404（区分"没这个权限"与"没有这个项目"）
            if (isMember(projectId, user.getUserId())) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "insufficient role for this operation (project member, global role required OPS or above)");
            }
            throw new BizException(ErrorCode.NOT_FOUND, "project not found");
        }
    }

    public void assertManageProject(Long projectId, AuthUser user) {
        requireProjectExists(projectId);
        if (!canManageProject(projectId, user)) {
            if (isMember(projectId, user.getUserId())) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "insufficient role for this operation (project member, global role required OPS or above)");
            }
            throw new BizException(ErrorCode.NOT_FOUND, "project not found");
        }
    }

    /** 新建项目后登记创建人为成员（create 调用方在同一事务内）。 */
    @Transactional
    public void registerMember(Long projectId, Long userId) {
        if (memberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            return;
        }
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setJoinedAt(LocalDateTime.now());
        memberRepository.save(m);
    }

    private void requireProjectExists(Long projectId) {
        if (projectId == null || !projectRepository.existsById(projectId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "project not found");
        }
    }

    // ---------- 成员管理 ----------

    /** 成员列表（附解析后的用户名）。调用方需先通过 assertView。 */
    public List<ProjectMemberVO> listMembers(Long projectId) {
        List<ProjectMemberEntity> members =
                memberRepository.findByProjectIdOrderByJoinedAtAsc(projectId);
        Map<Long, String> names = new HashMap<>();
        for (ProjectMemberEntity m : members) {
            names.computeIfAbsent(m.getUserId(),
                    id -> userRepository.findById(id).map(UserEntity::getUsername)
                            .orElse("user-" + id));
        }
        return members.stream()
                .map(m -> ProjectMemberVO.from(m, names.get(m.getUserId())))
                .collect(Collectors.toList());
    }

    /** 添加成员（ADMIN 或项目成员；重复 → CONFLICT）。 */
    @Transactional
    public ProjectMemberVO addMember(Long projectId, Long userId, AuthUser operator) {
        assertManageProject(projectId, operator);
        UserEntity target = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "user not found: " + userId));
        if (memberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "user " + target.getUsername() + " is already a member of this project");
        }
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setJoinedAt(LocalDateTime.now());
        memberRepository.save(m);
        return ProjectMemberVO.from(m, target.getUsername());
    }

    /** 移除成员（ADMIN 或项目成员）。 */
    @Transactional
    public void removeMember(Long projectId, Long userId, AuthUser operator) {
        assertManageProject(projectId, operator);
        ProjectMemberEntity m = requireMember(projectId, userId);
        memberRepository.delete(m);
    }

    private ProjectMemberEntity requireMember(Long projectId, Long userId) {
        return memberRepository.findById(new ProjectMemberId(projectId, userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "user is not a member of this project"));
    }

    /** 全库成员关系按 userId 分组（用户管理页展示"已分配项目"用，避免 N+1）。 */
    public Map<Long, List<Long>> memberProjectIdsByUser() {
        return memberRepository.findAll(Sort.by(Sort.Direction.ASC, "projectId")).stream()
                .collect(Collectors.groupingBy(
                        ProjectMemberEntity::getUserId,
                        Collectors.mapping(ProjectMemberEntity::getProjectId, Collectors.toList())));
    }
}
