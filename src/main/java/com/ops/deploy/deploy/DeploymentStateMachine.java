package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 部署任务状态机（纯静态，可单测）。
 *
 * <p>合法转换：
 * <ul>
 *   <li>主流程 PENDING → UPLOADING → INSTALLING → STARTING → VERIFYING → SUCCESS</li>
 *   <li>UPLOADING/INSTALLING/STARTING/VERIFYING 各可转 ROLLING_BACK（有旧版本可回滚时步骤失败直接进回滚）</li>
 *   <li>ROLLING_BACK → ROLLED_BACK（回滚成功）或 NEEDS_ATTENTION（回滚失败，人工接管）</li>
 *   <li>PENDING/UPLOADING → CANCELLED</li>
 *   <li>FAILED 为"无旧版本可回滚"时的失败终态，无出边</li>
 * </ul>
 * 终态（SUCCESS / FAILED / ROLLED_BACK / NEEDS_ATTENTION / CANCELLED）无出边；
 * 非法转换抛 {@code BizException(BAD_REQUEST, "illegal status transition: X -> Y")}。
 */
public final class DeploymentStateMachine {

    private static final Map<DeploymentStatus, Set<DeploymentStatus>> TRANSITIONS =
            new EnumMap<>(DeploymentStatus.class);

    private static final Set<DeploymentStatus> TERMINAL = EnumSet.of(
            DeploymentStatus.SUCCESS,
            DeploymentStatus.FAILED,
            DeploymentStatus.ROLLED_BACK,
            DeploymentStatus.NEEDS_ATTENTION,
            DeploymentStatus.CANCELLED);

    static {
        allow(DeploymentStatus.PENDING, DeploymentStatus.UPLOADING, DeploymentStatus.CANCELLED);
        allow(DeploymentStatus.UPLOADING, DeploymentStatus.INSTALLING,
                DeploymentStatus.ROLLING_BACK, DeploymentStatus.CANCELLED);
        allow(DeploymentStatus.INSTALLING, DeploymentStatus.STARTING, DeploymentStatus.ROLLING_BACK);
        allow(DeploymentStatus.STARTING, DeploymentStatus.VERIFYING, DeploymentStatus.ROLLING_BACK);
        allow(DeploymentStatus.VERIFYING, DeploymentStatus.SUCCESS, DeploymentStatus.ROLLING_BACK);
        allow(DeploymentStatus.ROLLING_BACK, DeploymentStatus.ROLLED_BACK, DeploymentStatus.NEEDS_ATTENTION);
        // 终态（SUCCESS / FAILED / ROLLED_BACK / NEEDS_ATTENTION / CANCELLED）无出边
    }

    private DeploymentStateMachine() {
    }

    private static void allow(DeploymentStatus from, DeploymentStatus... to) {
        Set<DeploymentStatus> set = EnumSet.noneOf(DeploymentStatus.class);
        for (DeploymentStatus s : to) {
            set.add(s);
        }
        TRANSITIONS.put(from, set);
    }

    /** 校验状态转换合法性；非法抛 {@code BizException(BAD_REQUEST)}。 */
    public static void assertTransition(DeploymentStatus from, DeploymentStatus to) {
        if (from == null || to == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "illegal status transition: " + from + " -> " + to);
        }
        Set<DeploymentStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "illegal status transition: " + from + " -> " + to);
        }
    }

    /** 是否终态：SUCCESS / FAILED / ROLLED_BACK / NEEDS_ATTENTION / CANCELLED。 */
    public static boolean isTerminal(DeploymentStatus status) {
        return status != null && TERMINAL.contains(status);
    }

    /** 是否活跃（占用部署槽位）：所有非终态，含 PENDING。 */
    public static boolean isActive(DeploymentStatus status) {
        return status != null && !TERMINAL.contains(status);
    }
}
