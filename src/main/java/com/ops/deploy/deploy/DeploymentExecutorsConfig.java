package com.ops.deploy.deploy;

import com.ops.deploy.database.DatabaseService;
import com.ops.deploy.project.PackageStorage;
import com.ops.deploy.ssh.SshService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 部署步骤执行器装配（Task 6 E）。
 *
 * <p>四个步骤执行器（上传/安装/启动/健康检查）为无状态纯逻辑类，统一在此注册为 Bean，
 * 供 {@link DeploymentRunner} 构造注入。健康检查的轮询次数 / 间隔可经 {@code deploy.verify.*}
 * 配置（默认 10 次 / 3000ms 间隔），单测直接 new 并传 0 间隔避免真实 sleep。
 */
@Configuration
public class DeploymentExecutorsConfig {

    @Bean
    public UploadStepExecutor uploadStepExecutor(PackageStorage packageStorage, SshService sshService) {
        return new UploadStepExecutor(packageStorage, sshService);
    }

    @Bean
    public InstallStepExecutor installStepExecutor(SshService sshService) {
        return new InstallStepExecutor(sshService);
    }

    @Bean
    public StartStepExecutor startStepExecutor(
            SshService sshService,
            @Value("${deploy.start.stable-checks:2}") int stableChecks,
            @Value("${deploy.start.stable-interval-ms:3000}") long stableIntervalMs,
            @Value("${deploy.start.smoke-test-seconds:5}") int smokeTestSeconds) {
        return new StartStepExecutor(sshService, stableChecks, stableIntervalMs, smokeTestSeconds);
    }

    @Bean
    public NginxStartExecutor nginxStartExecutor(SshService sshService) {
        return new NginxStartExecutor(sshService);
    }

    @Bean
    public DockerStartExecutor dockerStartExecutor(SshService sshService) {
        return new DockerStartExecutor(sshService);
    }

    /** PROCESS 模式包内文件覆盖执行器：按相对路径 fileKey 解压后覆盖包内配置文件（方案 C）。 */
    @Bean
    public ProcessConfigOverwriteExecutor processConfigOverwriteExecutor(SshService sshService) {
        return new ProcessConfigOverwriteExecutor(sshService);
    }

    @Bean
    public VerifyStepExecutor verifyStepExecutor(
            SshService sshService,
            @Value("${deploy.verify.attempts:10}") int attempts,
            @Value("${deploy.verify.interval-ms:3000}") long intervalMs) {
        return new VerifyStepExecutor(sshService, attempts, intervalMs);
    }

    /**
     * 数据库供给器：部署时解析 databaseId（null/0 不用、-1 自动建项目独立 MySQL 库、
     * 正值引用已登记库）为应用可消费的环境变量，供 START 步注入。
     */
    @Bean
    public DatabaseProvisioner databaseProvisioner(SshService sshService, DatabaseService databaseService) {
        return new DatabaseProvisioner(sshService, databaseService);
    }
}
