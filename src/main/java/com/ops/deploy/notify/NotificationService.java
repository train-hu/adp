package com.ops.deploy.notify;

import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 站内通知服务：未读列表（按可见项目隔离）、未读计数、标记已读。
 *
 * <p>list 仅返回未读（read_at IS NULL），按创建时间倒序最多 50 条；
 * 项目隔离：ADMIN 可见全部（visibleProjectIds 返回 null），否则仅可见项目内通知。
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final ProjectAccessService accessService;

    public NotificationService(NotificationRepository repository, ProjectAccessService accessService) {
        this.repository = repository;
        this.accessService = accessService;
    }

    /** 未读通知列表（按可见项目隔离，倒序 50 条）。 */
    public List<NotificationVO> list(AuthUser user) {
        List<Long> visible = accessService.visibleProjectIds(user);
        return repository.findTop50ByReadAtIsNullOrderByCreatedAtDesc().stream()
                .filter(n -> n.getProjectId() == null || visible == null || visible.contains(n.getProjectId()))
                .map(NotificationVO::from)
                .collect(Collectors.toList());
    }

    /** 未读数量（按可见项目隔离）。 */
    public long unreadCount(AuthUser user) {
        List<Long> visible = accessService.visibleProjectIds(user);
        return repository.findTop50ByReadAtIsNullOrderByCreatedAtDesc().stream()
                .filter(n -> n.getProjectId() == null || visible == null || visible.contains(n.getProjectId()))
                .count();
    }

    /** 全部未读标记已读，返回受影响条数。 */
    @Transactional
    public int readAll(AuthUser user) {
        return repository.markAllRead(LocalDateTime.now());
    }

    /** 单条标记已读（幂等：已读则不重复）。 */
    @Transactional
    public void read(Long id, AuthUser user) {
        repository.markRead(id, LocalDateTime.now());
    }
}
