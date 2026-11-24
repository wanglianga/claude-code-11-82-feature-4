package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 联合约束图中的一条线性整数约束：
 * sum(coeff[i] * x[i]) op rhs，op ∈ {=, >=, <=}。
 * 来源包括：已披露精确表（等式）、抑制单元阈值（>= 最小单元）、
 * 公开总数（等式）、固定表组联合核验。
 */
@Entity
@Table(name = "inference_constraint", indexes = {
        @Index(name = "idx_inf_constraint_space", columnList = "space_id"),
        @Index(name = "idx_inf_constraint_version", columnList = "analysis_version_id")
})
public class Constraint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "analysis_version_id", nullable = false)
    private Long analysisVersionId;

    /** 产生该约束的尝试/交付物（可空：公开总数等外部约束） */
    @Column(name = "source_attempt_id")
    private Long sourceAttemptId;

    @Column(name = "source_deliverable_id")
    private Long sourceDeliverableId;

    /** PUBLIC_TOTAL / DISCLOSED_EQUALITY / SUPPRESSION_THRESHOLD / FIXED_TABLE_SET */
    @Column(name = "constraint_kind", nullable = false)
    private String constraintKind;

    /** 变量键 -> 系数（JSON 对象，键为单元维度元组字符串） */
    @Column(name = "coeffs_json", nullable = false, length = 12000)
    private String coeffsJson;

    @Column(nullable = false, length = 4)
    private String operator;

    @Column(nullable = false)
    private int rhs;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getAnalysisVersionId() { return analysisVersionId; }
    public void setAnalysisVersionId(Long v) { this.analysisVersionId = v; }
    public Long getSourceAttemptId() { return sourceAttemptId; }
    public void setSourceAttemptId(Long v) { this.sourceAttemptId = v; }
    public Long getSourceDeliverableId() { return sourceDeliverableId; }
    public void setSourceDeliverableId(Long v) { this.sourceDeliverableId = v; }
    public String getConstraintKind() { return constraintKind; }
    public void setConstraintKind(String v) { this.constraintKind = v; }
    public String getCoeffsJson() { return coeffsJson; }
    public void setCoeffsJson(String v) { this.coeffsJson = v; }
    public String getOperator() { return operator; }
    public void setOperator(String v) { this.operator = v; }
    public int getRhs() { return rhs; }
    public void setRhs(int rhs) { this.rhs = rhs; }
    public long getCreatedAt() { return createdAt; }
}
