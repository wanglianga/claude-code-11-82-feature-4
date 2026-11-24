package com.sandbox.imaging.inference;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InferenceEngineTest {

    private final InferenceEngine engine = new InferenceEngine();
    private static final int MIN_UNIT = 5;

    /** 场景一：公开总数 15，A=14、B=1（B 为被抑制小单元，域 {0..4}） */
    @Test
    void scenario1_totalOnly_keepsBFivePossibilities() {
        // 仅公开总数 + B 的抑制域约束
        var constraints = List.of(
                new LinConstraint(Map.of("A", 1, "B", 1), "=", 15, "PUBLIC_TOTAL"),
                new LinConstraint(Map.of("B", 1), "<=", MIN_UNIT - 1, "SUPPRESSION_DOMAIN")
        );
        var result = engine.analyze(constraints, 15);
        assertTrue(result.feasible());
        assertEquals(List.of(0, 1, 2, 3, 4), result.domains().get("B"));
    }

    @Test
    void scenario1_disclosingA14_pinsBToOneAndBreaksSuppressionPromise() {
        // 再加“A 表精确披露 14”，同时抑制承诺要求 B<=4 且系统要求 B 不能唯一
        var constraints = List.of(
                new LinConstraint(Map.of("A", 1, "B", 1), "=", 15, "PUBLIC_TOTAL"),
                new LinConstraint(Map.of("B", 1), "<=", MIN_UNIT - 1, "SUPPRESSION_DOMAIN"),
                new LinConstraint(Map.of("A", 1), "=", 14, "DISCLOSED_EQUALITY")
        );
        var result = engine.analyze(constraints, 15);
        assertTrue(result.feasible());
        // B 被唯一推出为 1 —— 审查服务据此硬阻断
        assertEquals(List.of(1), result.domains().get("B"));
        assertEquals(1, result.domains().get("B").size());
    }

    /** 场景二：A=8、B=7，无小单元，两表都可披露 */
    @Test
    void scenario2_eightAndSeven_bothDisclosable() {
        var constraints = List.of(
                new LinConstraint(Map.of("A", 1, "B", 1), "=", 15, "PUBLIC_TOTAL"),
                new LinConstraint(Map.of("A", 1), "=", 8, "DISCLOSED_EQUALITY"),
                new LinConstraint(Map.of("B", 1), "=", 7, "DISCLOSED_EQUALITY")
        );
        var result = engine.analyze(constraints, 15);
        assertTrue(result.feasible());
        assertEquals(List.of(8), result.domains().get("A"));
        assertEquals(List.of(7), result.domains().get("B"));
    }

    /** 舍入披露：A 舍入到 5 的倍数得 15，则真实 A ∈ [13,17]，B 域相应收窄但不唯一 */
    @Test
    void roundedDisclosure_producesIntervalConstraint() {
        var constraints = List.of(
                new LinConstraint(Map.of("A", 1, "B", 1), "=", 15, "PUBLIC_TOTAL"),
                new LinConstraint(Map.of("B", 1), "<=", MIN_UNIT - 1, "SUPPRESSION_DOMAIN"),
                new LinConstraint(Map.of("A", 1), ">=", 13, "ROUNDED_INTERVAL"),
                new LinConstraint(Map.of("A", 1), "<=", 17, "ROUNDED_INTERVAL")
        );
        var result = engine.analyze(constraints, 15);
        assertTrue(result.feasible());
        // A>=13 → B<=2，B 从 {0..4} 收窄到 {0,1,2}，但仍不唯一
        assertEquals(List.of(0, 1, 2), result.domains().get("B"));
    }

    @Test
    void contradictorySystem_isInfeasible() {
        var constraints = List.of(
                new LinConstraint(Map.of("A", 1, "B", 1), "=", 15, "PUBLIC_TOTAL"),
                new LinConstraint(Map.of("A", 1), "=", 14, "DISCLOSED_EQUALITY"),
                new LinConstraint(Map.of("B", 1), ">=", 5, "THRESHOLD")
        );
        var result = engine.analyze(constraints, 15);
        assertFalse(result.feasible());
    }
}
