package com.ops.deploy.server;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.ResourceAccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 服务器管理服务：CRUD + TCP 连通性探测。
 *
 * <p>安全要点：
 * - 凭证（SSH 密码）只以 AES-256-GCM 密文落库（credential_cipher），明文只经
 *   {@link CredentialCryptoService} 加解密，{@code getDecryptedPassword} 供 SSH 执行使用，
 *   任何 VO/响应均不暴露明文或密文；
 * - 连通性测试为 TCP 端口可达性（{@link PortChecker}），SSH 握手+认证由 Task 4 增强；
 * - 可见性隔离：OPS 可见 = 任一 OPS 创建 ∪ 被管理员分配（{@link ResourceAccessService}），
 *   不可见资源对其不存在（统一 404，防枚举）。带 {@code For} 后缀的方法为
 *   Controller 入口（带用户上下文）；不带用户参数的方法供系统内部调用
 *   （SshService 取 VO/密码，部署流水线等），不做可见性过滤。
 */
@Service
public class ServerService {

    /** 连通性探测超时（毫秒）。 */
    private static final int PROBE_TIMEOUT_MS = 3000;

    private final ServerRepository serverRepository;
    private final CredentialCryptoService crypto;
    private final PortChecker portChecker;
    private final DeploymentRepository deploymentRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final ResourceAccessService accessService;

    public ServerService(ServerRepository serverRepository,
                         CredentialCryptoService crypto,
                         PortChecker portChecker,
                         DeploymentRepository deploymentRepository,
                         ServiceInstanceRepository serviceInstanceRepository,
                         ResourceAccessService accessService) {
        this.serverRepository = serverRepository;
        this.crypto = crypto;
        this.portChecker = portChecker;
        this.deploymentRepository = deploymentRepository;
        this.serviceInstanceRepository = serviceInstanceRepository;
        this.accessService = accessService;
    }

    @Transactional
    public ServerVO create(ServerCreateRequest req, Long createdBy) {
        if (serverRepository.existsByName(req.getName())) {
            throw new BizException(ErrorCode.CONFLICT, "server name already exists");
        }
        ServerEntity entity = new ServerEntity();
        entity.setName(req.getName());
        entity.setIp(req.getIp());
        entity.setSshPort(req.getSshPort() != null ? req.getSshPort() : 22);
        entity.setSshUser(req.getSshUser());
        entity.setAuthType(req.getAuthType() != null && !req.getAuthType().isEmpty()
                ? req.getAuthType() : AuthType.PASSWORD.name());
        entity.setCredentialCipher(crypto.encrypt(req.getPassword()));
        entity.setEnvTag(req.getEnvTag() != null && !req.getEnvTag().isEmpty()
                ? req.getEnvTag() : "dev");
        entity.setStatus(ServerStatus.UNKNOWN.name());
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(LocalDateTime.now());
        try {
            serverRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // 仅 (name) 唯一约束竞态（23505）→ CONFLICT；外键等其它违规交全局处理器归类
            if (e.getMessage() != null && e.getMessage().contains("23505")) {
                throw new BizException(ErrorCode.CONFLICT, "server name already exists");
            }
            throw e;
        }
        return ServerVO.from(entity);
    }

    /** 内部全量列表（部署流程/自动登记查重等系统调用用，不做可见性过滤）。 */
    public List<ServerVO> list() {
        return serverRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(ServerVO::from)
                .collect(Collectors.toList());
    }

    /** 当前用户可见列表（Controller 入口）：ADMIN 全部；OPS = OPS 创建 ∪ 被分配。 */
    public List<ServerVO> listFor(AuthUser user) {
        List<Long> visibleIds = accessService.visibleServerIds(
                user, serverRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
        if (visibleIds != null && visibleIds.isEmpty()) {
            return List.of();
        }
        List<ServerEntity> entities = visibleIds == null
                ? serverRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                : serverRepository.findByIdIn(visibleIds).stream()
                        .sorted(Comparator.comparing(ServerEntity::getId))
                        .collect(Collectors.toList());
        return entities.stream().map(ServerVO::from).collect(Collectors.toList());
    }

    /** 加载实体（不存在抛 404），供内部调用与用户上下文方法共用。 */
    public ServerEntity requireEntity(Long id) {
        return serverRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "server not found"));
    }

    public ServerVO get(Long id) {
        return ServerVO.from(requireEntity(id));
    }

    /** Controller 入口：不可见 → 404。 */
    public ServerVO viewFor(Long id, AuthUser user) {
        ServerEntity entity = requireEntity(id);
        accessService.assertViewServer(id, entity, user);
        return ServerVO.from(entity);
    }

    /** 内部更新（无用户上下文），供系统调用。 */
    @Transactional
    public ServerVO update(Long id, ServerUpdateRequest req) {
        return doUpdate(requireEntity(id), req);
    }

    /** Controller 入口：不可见 → 404，且要求全局 &ge; OPS。 */
    @Transactional
    public ServerVO updateFor(Long id, ServerUpdateRequest req, AuthUser user) {
        ServerEntity entity = requireEntity(id);
        accessService.assertManageServer(id, entity, user);
        return doUpdate(entity, req);
    }

    private ServerVO doUpdate(ServerEntity entity, ServerUpdateRequest req) {
        if (req.getName() != null && !req.getName().isEmpty()) {
            if (!req.getName().equals(entity.getName())
                    && serverRepository.existsByName(req.getName())) {
                throw new BizException(ErrorCode.CONFLICT, "server name already exists");
            }
            entity.setName(req.getName());
        }
        if (req.getIp() != null && !req.getIp().isEmpty()) {
            entity.setIp(req.getIp());
        }
        if (req.getSshPort() != null) {
            entity.setSshPort(req.getSshPort());
        }
        if (req.getSshUser() != null && !req.getSshUser().isEmpty()) {
            entity.setSshUser(req.getSshUser());
        }
        if (req.getAuthType() != null && !req.getAuthType().isEmpty()) {
            entity.setAuthType(req.getAuthType());
        }
        if (req.getEnvTag() != null && !req.getEnvTag().isEmpty()) {
            entity.setEnvTag(req.getEnvTag());
        }
        // password 为 null/空 → 保留原凭证；提供新值 → 重新加密
        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            entity.setCredentialCipher(crypto.encrypt(req.getPassword()));
        }

        serverRepository.save(entity);
        return ServerVO.from(entity);
    }

    /** 内部删除（无用户上下文），供系统调用。 */
    @Transactional
    public void delete(Long id) {
        doDelete(requireEntity(id));
    }

    /** Controller 入口：不可见 → 404，且要求全局 &ge; OPS。 */
    @Transactional
    public void deleteFor(Long id, AuthUser user) {
        ServerEntity entity = requireEntity(id);
        accessService.assertManageServer(id, entity, user);
        doDelete(entity);
    }

    private void doDelete(ServerEntity entity) {
        Long id = entity.getId();
        // deployment / service_instance 通过 NO ACTION 外键引用 server_info，直接删会抛
        // DataIntegrityViolationException 被全局兜底成无业务含义的 500；这里先查引用并给出
        // 明确原因（HTTP 409），前端 toastError 即可展示可读错误，避免误判删除成功。
        long deploymentRefs = deploymentRepository.findAll().stream()
                .filter(d -> id.equals(d.getServerId()))
                .count();
        long serviceRefs = serviceInstanceRepository.findByServerId(id).size();
        if (deploymentRefs > 0 || serviceRefs > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "server is referenced by deployment/service records, cannot delete");
        }
        serverRepository.delete(entity);
    }

    /** 内部连通性测试（无用户上下文）。 */
    @Transactional
    public ServerTestResultVO testConnection(Long id) {
        return doTest(requireEntity(id));
    }

    /** Controller 入口：不可见 → 404，且要求全局 &ge; OPS。 */
    @Transactional
    public ServerTestResultVO testConnectionFor(Long id, AuthUser user) {
        ServerEntity entity = requireEntity(id);
        accessService.assertManageServer(id, entity, user);
        return doTest(entity);
    }

    private ServerTestResultVO doTest(ServerEntity entity) {
        // 解密凭证（本任务 TCP 探测暂不直接使用，Task 4 SSH 执行将使用；
        // 此处先行校验凭证可解密，凭证损坏时提前暴露 CREDENTIAL_ERROR）
        crypto.decrypt(entity.getCredentialCipher());

        long startNanos = System.nanoTime();
        boolean reachable = portChecker.isReachable(entity.getIp(), entity.getSshPort(), PROBE_TIMEOUT_MS);
        long latencyMs = reachable ? (System.nanoTime() - startNanos) / 1_000_000L : -1;

        entity.setStatus(reachable ? ServerStatus.ONLINE.name() : ServerStatus.OFFLINE.name());
        entity.setLastCheckAt(LocalDateTime.now());
        serverRepository.save(entity);

        ServerTestResultVO res = new ServerTestResultVO();
        res.setReachable(reachable);
        res.setLatencyMs(latencyMs);
        res.setStatus(entity.getStatus());
        return res;
    }

    /**
     * 解密并返回服务器 SSH 凭证明文，供 SSH 执行（Task 4）使用。
     *
     * <p><b>切勿</b>将返回值放入任何 VO / HTTP 响应。
     */
    public String getDecryptedPassword(Long serverId) {
        ServerEntity entity = requireEntity(serverId);
        return crypto.decrypt(entity.getCredentialCipher());
    }
}
