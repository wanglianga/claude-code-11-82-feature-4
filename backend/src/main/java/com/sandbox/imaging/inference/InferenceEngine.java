package com.sandbox.imaging.inference;

import java.util.*;

/**
 * 推断审查引擎：在一组线性整数约束下计算各受保护单元的可行整数计数集合。
 *
 * 约束来源：公开总数（等式）、已披露精确表（等式）、抑制单元阈值（&gt;= 最小单元）。
 * 算法：界传播（bounds consistency）到不动点 + MRV 回溯枚举全部可行解；
 * 枚举触顶时 fail-closed（标记 capped，调用方必须阻断受影响输出）。
 */
public class InferenceEngine {

    private static final int MAX_SOLUTIONS = 2000;
    private static final int MAX_NODES = 200_000;

    /**
     * @param constraints 全部累计约束（历史已披露 + 本次候选）
     * @param universe    单元计数的自然上界（公开总数/队列规模）
     */
    public FeasibilityResult analyze(Collection<LinConstraint> constraints, int universe) {
        SortedSet<String> vars = new TreeSet<>();
        for (LinConstraint c : constraints) vars.addAll(c.coeff().keySet());

        Map<String, int[]> bounds = new HashMap<>();
        for (String v : vars) bounds.put(v, new int[]{0, Math.max(universe, 0)});

        PropagationResult pr = propagate(constraints, bounds);
        if (!pr.feasible()) {
            return new FeasibilityResult(false, Map.of(), false, pr.reason());
        }

        Map<String, Set<Integer>> domains = new TreeMap<>();
        for (String v : vars) domains.put(v, new TreeSet<>());

        EnumState state = new EnumState(constraints, new HashMap<>(pr.bounds()), domains);
        boolean capped = state.enumerate();

        Map<String, List<Integer>> domainLists = new TreeMap<>();
        if (capped) {
            // fail-closed：枚举不完整时退回到界传播区间（更宽），并由调用方阻断
            for (Map.Entry<String, int[]> e : state.bounds.entrySet()) {
                List<Integer> interval = new ArrayList<>();
                for (int x = e.getValue()[0]; x <= e.getValue()[1]; x++) interval.add(x);
                domainLists.put(e.getKey(), interval);
            }
            return new FeasibilityResult(true, domainLists, true,
                    "可行解枚举触顶，推断区间不完整，按 fail-closed 处理");
        }
        for (Map.Entry<String, Set<Integer>> e : domains.entrySet()) {
            domainLists.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return new FeasibilityResult(true, domainLists, false, "OK");
    }

    /** 界传播不动点，返回收敛后的界与可行性 */
    private PropagationResult propagate(Collection<LinConstraint> constraints,
                                        Map<String, int[]> initial) {
        Map<String, int[]> bounds = deepCopy(initial);
        boolean changed = true;
        int rounds = 0;
        while (changed) {
            changed = false;
            if (++rounds > 500) {
                return new PropagationResult(false, bounds, "界传播未收敛");
            }
            for (LinConstraint c : constraints) {
                // 每个变量在该约束下的最小/最大贡献
                Map<String, int[]> contrib = new HashMap<>();
                long L = 0, U = 0;
                for (Map.Entry<String, Integer> e : c.coeff().entrySet()) {
                    String v = e.getKey();
                    int a = e.getValue();
                    int[] b = bounds.get(v);
                    int p1 = a * b[0], p2 = a * b[1];
                    int cmin = Math.min(p1, p2), cmax = Math.max(p1, p2);
                    contrib.put(v, new int[]{cmin, cmax});
                    L += cmin;
                    U += cmax;
                }
                int rhs = c.rhs();
                switch (c.op()) {
                    case "=" -> {
                        if (L > rhs || U < rhs) {
                            return new PropagationResult(false, bounds,
                                    "约束无解: " + render(c) + " 可行和区间 [" + L + "," + U + "]");
                        }
                        for (Map.Entry<String, Integer> e : c.coeff().entrySet()) {
                            String v = e.getKey();
                            int a = e.getValue();
                            int[] cm = contrib.get(v);
                            long minOthers = L - cm[0], maxOthers = U - cm[1];
                            // a*x >= rhs-maxOthers 且 a*x <= rhs-minOthers
                            long lowNeed = rhs - maxOthers, highNeed = rhs - minOthers;
                            int[] tight = divideInterval(a, lowNeed, highNeed);
                            changed |= tighten(bounds, v, tight);
                        }
                    }
                    case ">=" -> {
                        if (L > rhs && false) { /* 下界超过 rhs 对 >= 无影响 */ }
                        if (U < rhs) {
                            return new PropagationResult(false, bounds,
                                    "约束无解: " + render(c) + " 最大和 " + U + " < " + rhs);
                        }
                        for (Map.Entry<String, Integer> e : c.coeff().entrySet()) {
                            String v = e.getKey();
                            int a = e.getValue();
                            int[] cm = contrib.get(v);
                            long maxOthers = U - cm[1];
                            // a*x >= rhs-maxOthers
                            int[] tight = divideLower(a, rhs - maxOthers);
                            changed |= tighten(bounds, v, tight);
                        }
                    }
                    case "<=" -> {
                        if (L > rhs) {
                            return new PropagationResult(false, bounds,
                                    "约束无解: " + render(c) + " 最小和 " + L + " > " + rhs);
                        }
                        for (Map.Entry<String, Integer> e : c.coeff().entrySet()) {
                            String v = e.getKey();
                            int a = e.getValue();
                            int[] cm = contrib.get(v);
                            long minOthers = L - cm[0];
                            // a*x <= rhs-minOthers
                            int[] tight = divideUpper(a, rhs - minOthers);
                            changed |= tighten(bounds, v, tight);
                        }
                    }
                    default -> throw new IllegalArgumentException("未知运算符: " + c.op());
                }
                for (int[] b : bounds.values()) {
                    if (b[0] > b[1]) {
                        return new PropagationResult(false, bounds, "变量域为空");
                    }
                }
            }
        }
        return new PropagationResult(true, bounds, "OK");
    }

    /** 解 low <= a*x <= high（a 可为负），返回 x 的整数 [lb,ub] */
    private int[] divideInterval(int a, long low, long high) {
        if (a > 0) return new int[]{(int) ceilDiv(low, a), (int) floorDiv(high, a)};
        // a<0：除以负数翻转
        return new int[]{(int) ceilDiv(high, a), (int) floorDiv(low, a)};
    }

    /** a*x >= need */
    private int[] divideLower(int a, long need) {
        if (a > 0) return new int[]{(int) ceilDiv(need, a), Integer.MAX_VALUE};
        return new int[]{Integer.MIN_VALUE, (int) floorDiv(need, a)};
    }

    /** a*x <= need */
    private int[] divideUpper(int a, long need) {
        if (a > 0) return new int[]{Integer.MIN_VALUE, (int) floorDiv(need, a)};
        return new int[]{(int) ceilDiv(need, a), Integer.MAX_VALUE};
    }

    private boolean tighten(Map<String, int[]> bounds, String v, int[] tight) {
        int[] b = bounds.get(v);
        int nlb = Math.max(b[0], tight[0] == Integer.MIN_VALUE ? b[0] : tight[0]);
        int nub = Math.min(b[1], tight[1] == Integer.MAX_VALUE ? b[1] : tight[1]);
        if (nlb > nub) {
            b[0] = 1; b[1] = 0; // 标记空域
            return true;
        }
        boolean changed = nlb != b[0] || nub != b[1];
        b[0] = nlb; b[1] = nub;
        return changed;
    }

    private static long floorDiv(long x, long y) {
        // y 可能为负；JDK Math.floorDiv 支持负除数
        return Math.floorDiv(x, y);
    }

    private static long ceilDiv(long x, long y) {
        return Math.ceilDiv(x, y);
    }

    private Map<String, int[]> deepCopy(Map<String, int[]> src) {
        Map<String, int[]> dst = new HashMap<>();
        for (Map.Entry<String, int[]> e : src.entrySet()) dst.put(e.getKey(), e.getValue().clone());
        return dst;
    }

    private String render(LinConstraint c) {
        return c.coeff() + " " + c.op() + " " + c.rhs();
    }

    /** 回溯枚举状态 */
    private class EnumState {
        final Collection<LinConstraint> constraints;
        final Map<String, int[]> bounds;
        final Map<String, Set<Integer>> domains;
        final List<String> varList;
        int solutions = 0;
        int nodes = 0;
        boolean capped = false;

        EnumState(Collection<LinConstraint> constraints, Map<String, int[]> bounds,
                  Map<String, Set<Integer>> domains) {
            this.constraints = constraints;
            this.bounds = bounds;
            this.domains = domains;
            this.varList = new ArrayList<>(bounds.keySet());
        }

        boolean enumerate() {
            dfs(0);
            return capped;
        }

        private void dfs(int idx) {
            if (capped) return;
            if (idx == varList.size()) {
                for (Map.Entry<String, int[]> e : bounds.entrySet()) {
                    domains.get(e.getKey()).add(e.getValue()[0]);
                }
                solutions++;
                if (solutions >= MAX_SOLUTIONS) capped = true;
                return;
            }
            if (++nodes > MAX_NODES) {
                capped = true;
                return;
            }
            // MRV：选择当前最小域的未赋值变量
            int pick = idx;
            int best = Integer.MAX_VALUE;
            for (int i = idx; i < varList.size(); i++) {
                int size = bounds.get(varList.get(i))[1] - bounds.get(varList.get(i))[0] + 1;
                if (size < best) { best = size; pick = i; }
            }
            String var = varList.get(pick);
            Collections.swap(varList, idx, pick);

            int[] original = bounds.get(var).clone();
            for (int val = original[0]; val <= original[1] && !capped; val++) {
                Map<String, int[]> snapshot = deepCopy(bounds);
                bounds.put(var, new int[]{val, val});
                PropagationResult pr = propagate(constraints, bounds);
                if (pr.feasible()) dfs(idx + 1);
                // 回滚
                bounds.clear();
                bounds.putAll(snapshot);
            }
            Collections.swap(varList, idx, pick);
        }
    }

    private record PropagationResult(boolean feasible, Map<String, int[]> bounds, String reason) {}
}
