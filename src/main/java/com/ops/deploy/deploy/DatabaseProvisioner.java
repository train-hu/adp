package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.database.DatabaseCreateRequest;
import com.ops.deploy.database.DatabaseEntity;
import com.ops.deploy.database.DatabaseService;
import com.ops.deploy.database.DatabaseVO;
import com.ops.deploy.database.DatabaseType;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 部署数据库供给器：把"项目独立库自动创建"与"已登记库引用"统一收敛为
 * 应用可消费的连接信息（环境变量），供 PROCESS/DOCKER 两种模式注入。
 *
 * <p>自动创建（databaseId = -1）：SSH 到目标机检查容器 {@code {project}-mysql}
 * （运行中/已停止均视为既有），既在则复用（容器停止则 docker start，数据卷持久化），
 * 否则起 mysql:8.0 容器（宿主机端口从 3306 起避让扫描、随机字母数字密码、
 * 持久卷 {@code {project}-mysql-data}），等待就绪后幂等建库 + 建应用账号，
 * 并把实例自动登记回 {@code database_info}（命名 {@code {project}-mysql}）。
 *
 * <p>安全：容器密码为 24 位纯字母数字（shell/SQL 元字符零风险，可安全内联进命令）；
 * 建库 SQL 经 base64 传递（含库名/账号，防任意字符逃逸）。明文密码只落
 * {@code database_info.password_cipher}（AES-256-GCM 加密），任何 VO 不暴露。
 */
public class DatabaseProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProvisioner.class);

    private static final String MYSQL_IMAGE = "mysql:8.0";
    /** mysql 容器启动 SSH 超时（拉镜像场景放宽到 5 分钟）。 */
    private static final int START_TIMEOUT_MS = 300_000;
    /** 就绪等待总时长（秒）与采样间隔（ms）。 */
    private static final int READY_TIMEOUT_S = 90;
    private static final int READY_INTERVAL_MS = 2000;
    /** 端口避让扫描上限（从 3306 起递增）。 */
    private static final int FREE_PORT_SCAN_LIMIT = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SshService sshService;
    private final DatabaseService databaseService;

    public DatabaseProvisioner(SshService sshService, DatabaseService databaseService) {
        this.sshService = sshService;
        this.databaseService = databaseService;
    }

    /**
     * 解析部署要使用的数据库，返回 (登记 id, 明文连接信息)。
     *
     * @param databaseId null/0 = 不使用数据库（返回 null）；-1 = 自动创建项目独立 MySQL 库；
     *                   其它正值 = 引用已登记数据库
     * @return 连接信息（host/port/database/username/password/type），password 可能为 null
     */
    public DbRef resolve(Long databaseId, long serverId, String projectName) {
        if (databaseId == null || databaseId == 0L) {
            return null;
        }
        if (databaseId == -1L) {
            return provisionIndependentMysql(serverId, projectName);
        }
        DatabaseEntity db = databaseService.require(databaseId);
        return toRef(db);
    }

    /** 连接信息（明文，仅供部署流程内部使用，禁止进任何 VO / HTTP 响应）。 */
    public record DbRef(Long databaseId, String type, String host, int port,
                        String database, String username, String password) {
        /**
         * 生成注入应用的环境变量（LinkedHashMap 保序）：
         * 通用 DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD/DB_TYPE +
         * Spring 习惯 SPRING_DATASOURCE_*（REDIS 无 datasource 概念，跳过该组）。
         */
        public Map<String, String> toEnvVars() {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("DB_TYPE", type == null ? "" : type);
            env.put("DB_HOST", host == null ? "" : host);
            env.put("DB_PORT", String.valueOf(port));
            env.put("DB_NAME", database == null ? "" : database);
            env.put("DB_USERNAME", username == null ? "" : username);
            env.put("DB_PASSWORD", password == null ? "" : password);
            if (!"REDIS".equals(type)) {
                env.put("SPRING_DATASOURCE_URL", buildJdbcUrl());
                env.put("SPRING_DATASOURCE_USERNAME", username == null ? "" : username);
                env.put("SPRING_DATASOURCE_PASSWORD", password == null ? "" : password);
            }
            return env;
        }

        /** jdbc:mysql://host:port/db?useSSL=false... / jdbc:postgresql://... / redis://host:port/db */
        public String buildJdbcUrl() {
            if ("POSTGRESQL".equals(type)) {
                return "jdbc:postgresql://" + host + ":" + port + "/" + database;
            }
            if ("REDIS".equals(type)) {
                String db = (database == null || database.isEmpty()) ? "" : database;
                return "redis://" + host + ":" + port + (db.isEmpty() ? "" : "/" + db);
            }
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        }
    }

    private DbRef toRef(DatabaseEntity db) {
        return new DbRef(db.getId(), db.getDbType(), db.getHost(), db.getPort(),
                db.getDatabaseName(), db.getUsername(), databaseService.getDecryptedPassword(db));
    }

    /**
     * 自动创建（或复用）项目独立 MySQL 库并登记，返回连接信息。
     * 幂等：同名容器已存在 → 复用（不重起、不换密码，数据卷持久化），仅确保库存在并回登记 id。
     */
    DbRef provisionIndependentMysql(long serverId, String projectName) {
        String safe = safeProjectName(projectName);
        String containerName = safe + "-mysql";
        String databaseName = safe;

        // 1. 已登记过（上次部署自动登记）→ 复用：确保容器在运行 + 库存在，回登记 id
        DatabaseVO registered = databaseService.list().stream()
                .filter(v -> containerName.equals(v.getName()))
                .findFirst()
                .orElse(null);
        if (registered != null) {
            DatabaseEntity entity = databaseService.require(registered.getId());
            ensureMysqlContainer(serverId, entity, databaseName);
            log.info("reuse auto-provisioned mysql {} for project {}", containerName, projectName);
            return toRef(entity);
        }

        // 2. 目标机已有同名容器（登记记录缺失）→ 密码不可知，移除重建（数据卷保留）
        if (containerExists(serverId, containerName)) {
            log.warn("container {} exists on server {} without a database_info record, "
                    + "recreating it to recover a known password", containerName, serverId);
            sshService.execute(serverId,
                    "docker rm -f " + ShellQuoter.quote(containerName), 60_000);
        }

        // 3. 分配宿主机端口（3306 起避让）
        Set<Integer> busy = probeBusyPorts(serverId);
        int hostPort = resolveMysqlPort(3306, busy);

        // 4. 生成随机密码并起容器（密码纯字母数字，shell 内联安全）
        String password = randomPassword(24);
        String startCmd = "docker run -d --name " + ShellQuoter.quote(containerName)
                + " -p " + hostPort + ":3306"
                + " -v " + ShellQuoter.quote(containerName + "-data") + ":/var/lib/mysql"
                + " -e MYSQL_ROOT_PASSWORD=" + password
                + " -e MYSQL_DATABASE=" + ShellQuoter.quote(databaseName)
                + " " + MYSQL_IMAGE;
        CommandResult r = sshService.executeWithRetry(serverId, startCmd, START_TIMEOUT_MS, 2);
        if (r.getTimedOut() || r.getExitCode() != 0) {
            String reason = r.getTimedOut() ? "timeout" : "exit=" + r.getExitCode();
            throw new BizException(ErrorCode.SSH_ERROR,
                    "start mysql container " + containerName + " failed (" + reason + "): "
                            + DockerStartExecutor.tailOf(r.getStdout(), r.getStderr(), 500));
        }

        // 5. 等待就绪 + 幂等建库/建账号
        waitMysqlReady(serverId, containerName, password);
        ensureDatabase(serverId, containerName, password, databaseName);

        // 6. 自动登记回 database_info（host 用 127.0.0.1：应用与容器同宿主机，走映射端口）
        DatabaseCreateRequest req = new DatabaseCreateRequest();
        req.setName(containerName);
        req.setDbType(DatabaseType.MYSQL.name());
        req.setHost("127.0.0.1");
        req.setPort(hostPort);
        req.setDatabaseName(databaseName);
        req.setUsername("root");
        req.setPassword(password);
        DatabaseVO vo = databaseService.create(req);
        log.info("auto-provisioned mysql {} on server {} ({}:{}) and registered as database {}",
                containerName, serverId, hostPort, databaseName, vo.getId());
        return new DbRef(vo.getId(), DatabaseType.MYSQL.name(), "127.0.0.1", hostPort,
                databaseName, "root", password);
    }

    /**
     * 确保既有容器在运行且目标库存在（复用路径）：容器不在 → 按登记记录重起（数据卷保留）；
     * 容器在但已停止 → docker start；就绪后幂等补建库/账号。
     */
    private void ensureMysqlContainer(long serverId, DatabaseEntity db, String databaseName) {
        String container = db.getName();
        String password = databaseService.getDecryptedPassword(db);
        if (!containerRunning(serverId, container)) {
            if (containerExists(serverId, container)) {
                CommandResult s = sshService.execute(serverId,
                        "docker start " + ShellQuoter.quote(container), 60_000);
                if (s == null || s.getTimedOut() || s.getExitCode() != 0) {
                    throw new BizException(ErrorCode.SSH_ERROR,
                            "docker start " + container + " failed: "
                                    + (s == null ? "no result" : DockerStartExecutor.tailOf(s.getStdout(), s.getStderr(), 300)));
                }
            } else {
                String startCmd = "docker run -d --name " + ShellQuoter.quote(container)
                        + " -p " + db.getPort() + ":3306"
                        + " -v " + ShellQuoter.quote(container + "-data") + ":/var/lib/mysql"
                        + " -e MYSQL_ROOT_PASSWORD=" + password
                        + " -e MYSQL_DATABASE=" + ShellQuoter.quote(databaseName)
                        + " " + MYSQL_IMAGE;
                CommandResult r = sshService.executeWithRetry(serverId, startCmd, START_TIMEOUT_MS, 2);
                if (r.getTimedOut() || r.getExitCode() != 0) {
                    String reason = r.getTimedOut() ? "timeout" : "exit=" + r.getExitCode();
                    throw new BizException(ErrorCode.SSH_ERROR,
                            "restart mysql container " + container + " failed (" + reason + "): "
                                    + DockerStartExecutor.tailOf(r.getStdout(), r.getStderr(), 500));
                }
            }
            waitMysqlReady(serverId, container, password);
        }
        // 容器在运行也幂等补建库/账号（防数据卷初始化时库名不一致等场景）
        ensureDatabase(serverId, container, password, databaseName);
    }

    /** mysqladmin ping 轮询直至就绪（超时抛 SSH_ERROR）。 */
    private void waitMysqlReady(long serverId, String container, String password) {
        String pingCmd = "docker exec " + ShellQuoter.quote(container)
                + " mysqladmin ping -h127.0.0.1 -uroot -p" + password + " 2>&1";
        for (int waited = 0; waited < READY_TIMEOUT_S; waited += READY_INTERVAL_MS / 1000) {
            try {
                CommandResult p = sshService.execute(serverId, pingCmd, 30_000);
                if (p != null && p.getExitCode() == 0
                        && p.getStdout() != null && p.getStdout().contains("mysqld is alive")) {
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("mysql ready probe failed (retrying): {}", e.getMessage());
            }
            sleepQuietly(READY_INTERVAL_MS);
        }
        throw new BizException(ErrorCode.SSH_ERROR,
                "mysql container " + container + " not ready after " + READY_TIMEOUT_S + "s");
    }

    /**
     * 幂等建库 + 建应用账号（账号 = 库名，密码与 root 相同，简化应用配置）。
     * SQL 经 base64 传递（库名/账号已白名单化，双重保险）。
     */
    private void ensureDatabase(long serverId, String container, String password, String databaseName) {
        String sql = "CREATE DATABASE IF NOT EXISTS `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
                + " CREATE USER IF NOT EXISTS '" + databaseName
                + "'@'%' IDENTIFIED BY '" + password + "';"
                + " GRANT ALL PRIVILEGES ON `" + databaseName + "`.* TO '" + databaseName
                + "'@'%'; FLUSH PRIVILEGES;";
        String b64sql = Base64.getEncoder().encodeToString(sql.getBytes(StandardCharsets.UTF_8));
        String cmd = "docker exec " + ShellQuoter.quote(container)
                + " sh -c 'printf %s " + b64sql + " | base64 -d | mysql -h127.0.0.1 -uroot -p"
                + password + "'";
        CommandResult r = sshService.execute(serverId, cmd, 60_000);
        if (r == null || r.getTimedOut()) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "create database in container " + container + " timed out");
        }
        if (r.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "create database in container " + container + " failed (exit=" + r.getExitCode() + "): "
                            + DockerStartExecutor.tailOf(r.getStdout(), r.getStderr(), 500));
        }
    }

    /** 容器是否存在（运行中或已停止均算存在）。 */
    boolean containerExists(long serverId, String containerName) {
        try {
            CommandResult r = sshService.execute(serverId,
                    "docker ps -a --format '{{.Names}}' | grep -x "
                            + ShellQuoter.quote(containerName), 30_000);
            return r != null && r.getExitCode() == 0
                    && r.getStdout() != null && r.getStdout().trim().equals(containerName);
        } catch (RuntimeException e) {
            log.warn("probe container {} on server {} failed: {}", containerName, serverId, e.getMessage());
            return false;
        }
    }

    /** 容器是否运行中。 */
    boolean containerRunning(long serverId, String containerName) {
        try {
            CommandResult r = sshService.execute(serverId,
                    "docker ps --format '{{.Names}}' | grep -x "
                            + ShellQuoter.quote(containerName), 30_000);
            return r != null && r.getExitCode() == 0
                    && r.getStdout() != null && r.getStdout().trim().equals(containerName);
        } catch (RuntimeException e) {
            log.warn("probe running container {} on server {} failed: {}", containerName, serverId, e.getMessage());
            return false;
        }
    }

    /** 探测目标机已监听端口（尽力而为，同 {@code DockerStartExecutor#probeBusyPorts} 口径）。 */
    Set<Integer> probeBusyPorts(long serverId) {
        Set<Integer> busy = new HashSet<>();
        try {
            CommandResult r = sshService.execute(serverId, "ss -lnt", 10_000);
            if (r == null || r.getTimedOut() || r.getExitCode() != 0 || r.getStdout() == null) {
                return busy;
            }
            for (String line : r.getStdout().split("\\R")) {
                String s = line.trim();
                if (s.isEmpty() || !s.toUpperCase().startsWith("LISTEN")) {
                    continue;
                }
                String[] cols = s.split("\\s+");
                if (cols.length < 4) {
                    continue;
                }
                String local = cols[3];
                int ci = local.lastIndexOf(':');
                if (ci < 0 || ci == local.length() - 1) {
                    continue;
                }
                try {
                    busy.add(Integer.parseInt(local.substring(ci + 1)));
                } catch (NumberFormatException ignored) {
                    // 非数字端口（unix socket 等），忽略
                }
            }
        } catch (RuntimeException e) {
            log.warn("probe busy ports on server {} failed: {}", serverId, e.getMessage());
        }
        return busy;
    }

    /** 从 start 起递增找第一个空闲端口（上限 {@link #FREE_PORT_SCAN_LIMIT} 次，超 65535 回落 start）。 */
    static int resolveMysqlPort(int start, Set<Integer> busy) {
        for (int p = start; p <= 65535 && p < start + FREE_PORT_SCAN_LIMIT; p++) {
            if (!busy.contains(p)) {
                return p;
            }
        }
        return start;
    }

    /** 24 位字母数字随机密码（无 shell/SQL 元字符，可安全内联进命令）。 */
    static String randomPassword(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 项目名 → 容器/库名安全前缀（仅保留 [a-z0-9-]，防 docker/mysql 标识符注入）。 */
    static String safeProjectName(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "project name required for independent database");
        }
        StringBuilder sb = new StringBuilder();
        for (char c : projectName.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                sb.append(c);
            } else if (c == '_') {
                sb.append('-');
            }
        }
        String name = sb.toString();
        return name.isEmpty() ? "app" : name;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SSH_ERROR, "interrupted while waiting for mysql ready");
        }
    }
}
