package com.ops.deploy.deploy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 部署触发器（独立 Bean）：事务提交后调用 {@link DeploymentRunner}。
 *
 * <p>本类<b>不</b>加 {@code @Async}——它必须在 {@code create()} 的事务内同步执行，
 * 以便在 {@code afterCommit} 回调里注册；真正的异步在 {@code DeploymentRunner.run}
 * 上（{@code @Async}，经代理生效）。独立 Bean 也避免 self-invocation 绕过代理。
 * 无事务上下文（单测同步调用）时直接执行。
 */
@Component
public class DeploymentTrigger {

    private static final Logger log = LoggerFactory.getLogger(DeploymentTrigger.class);

    private final DeploymentRunner runner;

    public DeploymentTrigger(DeploymentRunner runner) {
        this.runner = runner;
    }

    /**
     * 触发部署执行：有活动事务 → 提交后执行；否则（单测直接调用）立即执行。
     */
    public void trigger(Long deploymentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("deployment {} committed, starting async run", deploymentId);
                    runner.run(deploymentId);
                }
            });
        } else {
            runner.run(deploymentId);
        }
    }
}
