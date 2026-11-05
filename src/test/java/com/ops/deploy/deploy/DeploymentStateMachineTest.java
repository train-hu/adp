package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署状态机转换表测试：覆盖全部合法转换与全部非法组合。
 */
class DeploymentStateMachineTest {

    /** 规格定义的合法转换表。 */
    private static final Map<DeploymentStatus, Set<DeploymentStatus>> EXPECTED =
            new EnumMap<>(DeploymentStatus.class);

    static {
        allow(DeploymentStatus.PENDING, DeploymentStatus.UPLOADING, DeploymentStatus.CANCELLED);
        allow(DeploymentStatus.UPLOADING, DeploymentStatus.INSTALLING,
                DeploymentStatus.ROLLING_BACK, DeploymentStatus.CANCELLED);
        allow(DeploymentStatus.INSTALLING, DeploymentStatus.STARTING, DeploymentStatus.ROLLING_BACK);
        allow(DeploymentStatus.STARTING, DeploymentStatus.VERIFYING, DeploymentStatus.ROLLING_BACK);
        allow(DeploymentStatus.VERIFYING, DeploymentStatus.SUCCESS, DeploymentStatus.ROLLING_BACK);
        allow(DeploymentStatus.ROLLING_BACK, DeploymentStatus.ROLLED_BACK, DeploymentStatus.NEEDS_ATTENTION);
        // 终态 SUCCESS / FAILED / ROLLED_BACK / NEEDS_ATTENTION / CANCELLED 无出边
    }

    private static void allow(DeploymentStatus from, DeploymentStatus... to) {
        Set<DeploymentStatus> set = EnumSet.noneOf(DeploymentStatus.class);
        for (DeploymentStatus s : to) {
            set.add(s);
        }
        EXPECTED.put(from, set);
    }

    @Test
    @DisplayName("全部合法转换均放行")
    void legalTransitionsAreAllowed() {
        int count = 0;
        for (Map.Entry<DeploymentStatus, Set<DeploymentStatus>> e : EXPECTED.entrySet()) {
            for (DeploymentStatus to : e.getValue()) {
                assertDoesNotThrow(
                        () -> DeploymentStateMachine.assertTransition(e.getKey(), to),
                        e.getKey() + " -> " + to + " 应合法");
                count++;
            }
        }
        assertTrue(count >= 13, "合法转换数量应 >= 13，实际 " + count);
    }

    @Test
    @DisplayName("其余全部组合（含自转换与终态出边）均抛 BAD_REQUEST")
    void allOtherTransitionsAreRejected() {
        int count = 0;
        for (DeploymentStatus from : DeploymentStatus.values()) {
            for (DeploymentStatus to : DeploymentStatus.values()) {
                Set<DeploymentStatus> allowed = EXPECTED.get(from);
                boolean expectedLegal = allowed != null && allowed.contains(to);
                if (expectedLegal) {
                    continue;
                }
                BizException ex = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                        () -> DeploymentStateMachine.assertTransition(from, to),
                        from + " -> " + to + " 应非法");
                assertEquals(ErrorCode.BAD_REQUEST, ex.getCode());
                assertTrue(ex.getMessage().contains("illegal status transition"),
                        "错误消息应包含 illegal status transition，实际: " + ex.getMessage());
                count++;
            }
        }
        assertTrue(count >= 100, "非法组合数量应 >= 100，实际 " + count);
    }

    @Test
    @DisplayName("终态集合：SUCCESS/FAILED/ROLLED_BACK/NEEDS_ATTENTION/CANCELLED")
    void terminalStates() {
        List<DeploymentStatus> terminals = Stream.of(
                DeploymentStatus.SUCCESS,
                DeploymentStatus.FAILED,
                DeploymentStatus.ROLLED_BACK,
                DeploymentStatus.NEEDS_ATTENTION,
                DeploymentStatus.CANCELLED).toList();
        for (DeploymentStatus s : DeploymentStatus.values()) {
            assertEquals(terminals.contains(s), DeploymentStateMachine.isTerminal(s),
                    "isTerminal(" + s + ") 断言失败");
        }
    }

    @Test
    @DisplayName("活跃状态 = 非终态（PENDING 也占用部署槽位）")
    void activeStatesAreNonTerminal() {
        for (DeploymentStatus s : DeploymentStatus.values()) {
            assertEquals(!DeploymentStateMachine.isTerminal(s), DeploymentStateMachine.isActive(s),
                    "isActive(" + s + ") 断言失败");
        }
        List<DeploymentStatus> expectedActive = Stream.of(DeploymentStatus.values())
                .filter(s -> !DeploymentStateMachine.isTerminal(s))
                .sorted()
                .collect(Collectors.toList());
        assertEquals(List.of(DeploymentStatus.PENDING, DeploymentStatus.UPLOADING,
                        DeploymentStatus.INSTALLING, DeploymentStatus.STARTING,
                        DeploymentStatus.VERIFYING, DeploymentStatus.ROLLING_BACK),
                expectedActive);
    }

    @Test
    @DisplayName("FAILED 是终态：任何从 FAILED 出发的转换均非法")
    void failedIsTerminal() {
        for (DeploymentStatus to : DeploymentStatus.values()) {
            org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                    () -> DeploymentStateMachine.assertTransition(DeploymentStatus.FAILED, to));
        }
        assertFalse(DeploymentStateMachine.isActive(DeploymentStatus.FAILED));
    }

    @Test
    @DisplayName("错误消息格式包含源态与目标态")
    void errorMessageContainsFromAndTo() {
        BizException ex = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> DeploymentStateMachine.assertTransition(DeploymentStatus.PENDING,
                        DeploymentStatus.SUCCESS));
        assertTrue(ex.getMessage().contains("PENDING"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SUCCESS"), ex.getMessage());
    }
}
