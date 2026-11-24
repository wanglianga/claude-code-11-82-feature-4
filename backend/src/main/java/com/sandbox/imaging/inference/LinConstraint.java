package com.sandbox.imaging.inference;

import java.util.Map;

/**
 * 线性整数约束：sum(coeff[var] * x[var]) op rhs。
 * 变量键为单元维度元组的规范字符串，例如 "hospital=A"、"hospital=A|sex=F"、"TOTAL"。
 */
public record LinConstraint(Map<String, Integer> coeff, String op, int rhs, String kind) {

    public boolean isEquality() {
        return "=".equals(op);
    }
}
