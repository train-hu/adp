package com.ops.deploy.deploy;

/**
 * 部署任务状态（以 name 字符串落库 deployment.status，不用 JPA 枚举映射）。
 *
 * <p>主流程：PENDING → UPLOADING → INSTALLING → STARTING → VERIFYING → SUCCESS。
 * 有旧版本时步骤失败直接 ROLLING_BACK → ROLLED_BACK / NEEDS_ATTENTION（不经过 FAILED）；
 * 无旧版本时步骤失败终态为 FAILED；PENDING/UPLOADING 可 CANCELLED。
 * 终态：SUCCESS / FAILED / ROLLED_BACK / NEEDS_ATTENTION / CANCELLED。
 */
public enum DeploymentStatus {
    PENDING,
    UPLOADING,
    INSTALLING,
    STARTING,
    VERIFYING,
    SUCCESS,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
    NEEDS_ATTENTION,
    CANCELLED
}
