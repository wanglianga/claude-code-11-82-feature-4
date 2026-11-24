package com.sandbox.imaging.service;

/**
 * 对外输出单元。绝不携带抑制单元的真实计数——
 * SUPPRESSED 时 displayCount 为 null，真实值只存在内部 RawTable 与审计台账。
 */
public class OutputCell {
    public String dimKey;
    public String status;
    public Integer displayCount;
    public int visitCount;
    public Integer roundingStep;
    public Double avgMaxMm;
    public Double avgLesionCount;
}
