package com.ops.deploy.deploy;

/**
 * 部署步骤名称（落库 deployment_step.name）。
 */
public enum DeploymentStepName {
    UPLOAD,
    INSTALL,
    START,
    VERIFY,
    ROLLBACK
}
