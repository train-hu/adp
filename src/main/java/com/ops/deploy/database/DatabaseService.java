package com.ops.deploy.database;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.ResourceAccessService;
import com.ops.deploy.server.PortChecker;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库连接管理服务：CRUD + TCP 连通性探测。
 *
 * <p>安全要点：
 * - 密码只以 AES-256-GCM 密文落库（password_cipher，可空 = 无密码实例），明文只经
 *   {@link CredentialCryptoService} 加解密，{@code getDecryptedPassword} 仅供部署流程
 *   注入连接信息使用，任何 VO/响应均不暴露明文或密文；
 * - 连通性测试为 TCP 端口可达性（{@link PortChecker}，与 {@code ServerService} 同口径），
 *   数据库协议层握手校验依赖部署阶段的真实连接；
 * - 可见性隔离：OPS 可见 = 任一 OPS 创建 ∪ 被管理员分配（{@link ResourceAccessService}），
 *   不可见资源对其不存在（统一 404，防枚举）。带 {@code For} 后缀的方法为
 *   Controller 入口（带用户上下文）；不带用户参数的方法供系统内部调用
 *   （DatabaseProvisioner 自动建库、部署流水线等），不做可见性过滤。
 */
@Service
public class DatabaseService {

    /** 连通性探测超时（毫秒），与 ServerService 同口径。 */
    private static final int PROBE_TIMEOUT_MS = 3000;

    private final DatabaseRepository databaseRepository;
    private final CredentialCryptoService crypto;
    private final PortChecker portChecker;
    private final DeploymentRepository deploymentRepository;
    private final ResourceAccessService accessService;

    public DatabaseService(DatabaseRepository databaseRepository,
                           CredentialCryptoService crypto,
                           PortChecker portChecker,
                           DeploymentRepository deploymentRepository,
                           ResourceAccessService accessService) {
        this.databaseRepository = databaseRepository;
        this.crypto = crypto;
        this.portChecker = portChecker;
        this.deploymentRepository = deploymentRepository;
        this.accessService = accessService;
    }

    /** 内部创建（createdBy = null，归属存量资源），供 DatabaseProvisioner 自动建库使用。 */
    @Transactional
    public DatabaseVO create(DatabaseCreateRequest req) {
        return doCreate(req, null);
    }

    /** Controller 入口创建：记录创建人（OPS 创建的资源对全体 OPS 可见）。 */
    @Transactional
    public DatabaseVO create(DatabaseCreateRequest req, Long createdBy) {
        return doCreate(req, createdBy);
    }

    private DatabaseVO doCreate(DatabaseCreateRequest req, Long createdBy) {
        if (databaseRepository.existsByName(req.getName())) {
            throw new BizException(ErrorCode.CONFLICT, "database name already exists");
        }
        DatabaseType type = DatabaseType.parse(req.getDbType());
        if (type == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "unsupported dbType: " + req.getDbType());
        }
        DatabaseEntity entity = new DatabaseEntity();
        entity.setName(req.getName());
        entity.setDbType(type.name());
        entity.setHost(req.getHost());
        entity.setPort(req.getPort() != null ? req.getPort() : type.getDefaultPort());
        entity.setDatabaseName(req.getDatabaseName());
        entity.setUsername(req.getUsername());
        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            entity.setPasswordCipher(crypto.encrypt(req.getPassword()));
        }
        entity.setStatus(DatabaseStatus.UNKNOWN.name());
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(LocalDateTime.now());
        try {
            databaseRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // 仅 (name) 唯一约束竞态（23505）→ CONFLICT；其它违规交全局处理器归类
            if (e.getMessage() != null && e.getMessage().contains("23505")) {
                throw new BizException(ErrorCode.CONFLICT, "database name already exists");
            }
            throw e;
        }
        return DatabaseVO.from(entity);
    }

    /** 内部全量列表（部署流程/自动登记查重等系统调用用，不做可见性过滤）。 */
    public List<DatabaseVO> list() {
        return databaseRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(DatabaseVO::from)
                .collect(Collectors.toList());
    }

    /** 当前用户可见列表（Controller 入口）：ADMIN 全部；OPS = OPS 创建 ∪ 被分配。 */
    public List<DatabaseVO> listFor(AuthUser user) {
        List<Long> visibleIds = accessService.visibleDatabaseIds(
                user, databaseRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
        if (visibleIds != null && visibleIds.isEmpty()) {
            return List.of();
        }
        List<DatabaseEntity> entities = visibleIds == null
                ? databaseRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                : databaseRepository.findByIdIn(visibleIds).stream()
                        .sorted(Comparator.comparing(DatabaseEntity::getId))
                        .collect(Collectors.toList());
        return entities.stream().map(DatabaseVO::from).collect(Collectors.toList());
    }

    /** 加载实体（不存在抛 404），供部署流程与用户上下文方法共用。 */
    public DatabaseEntity require(Long id) {
        return databaseRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "database not found"));
    }

    /** 内部详情（无用户上下文），供系统调用。 */
    public DatabaseVO get(Long id) {
        return DatabaseVO.from(require(id));
    }

    /** Controller 入口：不可见 → 404。 */
    public DatabaseVO viewFor(Long id, AuthUser user) {
        DatabaseEntity entity = require(id);
        accessService.assertViewDatabase(id, entity, user);
        return DatabaseVO.from(entity);
    }

    /** 内部更新（无用户上下文），供系统调用。 */
    @Transactional
    public DatabaseVO update(Long id, DatabaseUpdateRequest req) {
        return doUpdate(require(id), req);
    }

    /** Controller 入口：不可见 → 404，且要求全局 &ge; OPS。 */
    @Transactional
    public DatabaseVO updateFor(Long id, DatabaseUpdateRequest req, AuthUser user) {
        DatabaseEntity entity = require(id);
        accessService.assertManageDatabase(id, entity, user);
        return doUpdate(entity, req);
    }

    private DatabaseVO doUpdate(DatabaseEntity entity, DatabaseUpdateRequest req) {
        if (req.getName() != null && !req.getName().isEmpty()) {
            if (!req.getName().equals(entity.getName())
                    && databaseRepository.existsByName(req.getName())) {
                throw new BizException(ErrorCode.CONFLICT, "database name already exists");
            }
            entity.setName(req.getName());
        }
        if (req.getDbType() != null && !req.getDbType().isEmpty()) {
            DatabaseType type = DatabaseType.parse(req.getDbType());
            if (type == null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "unsupported dbType: " + req.getDbType());
            }
            entity.setDbType(type.name());
        }
        if (req.getHost() != null && !req.getHost().isEmpty()) {
            entity.setHost(req.getHost());
        }
        if (req.getPort() != null) {
            entity.setPort(req.getPort());
        }
        if (req.getDatabaseName() != null) {
            entity.setDatabaseName(req.getDatabaseName());
        }
        if (req.getUsername() != null) {
            entity.setUsername(req.getUsername());
        }
        // password 为 null/空 → 保留原凭证；提供新值 → 重新加密
        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            entity.setPasswordCipher(crypto.encrypt(req.getPassword()));
        }

        databaseRepository.save(entity);
        return DatabaseVO.from(entity);
    }

    /** 内部删除（无用户上下文），供系统调用。 */
    @Transactional
    public void delete(Long id) {
        doDelete(require(id));
    }

    /** Controller 入口：不可见 → 404，且要求全局 &ge; OPS。 */
    @Transactional
    public void deleteFor(Long id, AuthUser user) {
        DatabaseEntity entity = require(id);
        accessService.assertManageDatabase(id, entity, user);
        doDelete(entity);
    }

    private void doDelete(DatabaseEntity entity) {
        Long id = entity.getId();
        // deployment.database_id 为 NO ACTION 外键引用 database_info：被任一部署引用时
        // 直接删会抛 DataIntegrityViolationException 兜底成 500，这里先查引用给明确 409。
        long deploymentRefs = deploymentRepository.findAll().stream()
                .filter(d -> id.equals(d.getDatabaseId()))
                .count();
        if (deploymentRefs > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "database is referenced by deployment records, cannot delete");
        }
        databaseRepository.delete(entity);
    }

    /** 内部连通性测试（无用户上下文）。 */
    @Transactional
    public DatabaseTestResultVO testConnection(Long id) {
        return doTest(require(id));
    }

    /** Controller 入口：不可见 → 404，且要求全局 &ge; OPS。 */
    @Transactional
    public DatabaseTestResultVO testConnectionFor(Long id, AuthUser user) {
        DatabaseEntity entity = require(id);
        accessService.assertManageDatabase(id, entity, user);
        return doTest(entity);
    }

    private DatabaseTestResultVO doTest(DatabaseEntity entity) {
        // 先校验凭证可解密（密文损坏时提前暴露 CREDENTIAL_ERROR，而非部署时才发现）
        if (entity.getPasswordCipher() != null) {
            crypto.decrypt(entity.getPasswordCipher());
        }

        long startNanos = System.nanoTime();
        boolean reachable = portChecker.isReachable(entity.getHost(), entity.getPort(), PROBE_TIMEOUT_MS);
        long latencyMs = reachable ? (System.nanoTime() - startNanos) / 1_000_000L : -1;

        entity.setStatus(reachable ? DatabaseStatus.ONLINE.name() : DatabaseStatus.OFFLINE.name());
        entity.setLastCheckAt(LocalDateTime.now());
        databaseRepository.save(entity);

        DatabaseTestResultVO res = new DatabaseTestResultVO();
        res.setReachable(reachable);
        res.setLatencyMs(latencyMs);
        res.setStatus(entity.getStatus());
        return res;
    }

    /**
     * 解密并返回数据库密码明文（无密码实例返回 null），仅供部署流程注入连接信息使用。
     *
     * <p><b>切勿</b>将返回值放入任何 VO / HTTP 响应。
     */
    public String getDecryptedPassword(DatabaseEntity entity) {
        if (entity.getPasswordCipher() == null) {
            return null;
        }
        return crypto.decrypt(entity.getPasswordCipher());
    }
}
