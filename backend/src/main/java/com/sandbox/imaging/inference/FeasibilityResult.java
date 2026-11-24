package com.sandbox.imaging.inference;

import java.util.List;
import java.util.Map;

/**
 * 一次可行性分析的结果：
 * - feasible：约束系统是否存在可行整数解
 * - domains：每个变量的可行整数计数集合（升序去重）
 * - capped：枚举触顶时为 true，域按界传播区间保守给出
 */
public record FeasibilityResult(boolean feasible,
                                Map<String, List<Integer>> domains,
                                boolean capped,
                                String reason) {
}
