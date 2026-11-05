package com.ops.deploy.deploy;

/**
 * 部署步骤状态（落库 deployment_step.status）。
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
