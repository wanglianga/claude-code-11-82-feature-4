package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/** 一次查询/发布尝试的完整记录：规范化语义、联合推断报告、预算扣减与裁决 */
@Entity
@Table(name = "query_attempt", indexes = {
        @Index(name = "idx_attempt_version", columnList = "analysis_version_id"),
        @Index(name = "idx_attempt_session", columnList = "session_id")
})
public class QueryAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "analysis_version_id", nullable = false)
    private Long analysisVersionId;

    @Column(name = "session_id")
    private Long sessionId;

    /** FIXED_TABLE_SET / INTERACTIVE */
    @Column(name = "route", nullable = false)
    private String route;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "canonical_query_json", nullable = false, length = 12000)
    private String canonicalQueryJson;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    /** 计算得到的原始结果（含抑制前计数），仅内部留存，对外输出走脱敏结果 */
    @Column(name = "raw_result_json", nullable = false, length = 12000)
    private String rawResultJson;

    /** 对外结果：抑制单元标记 SUPPRESSED、舍入后数值 */
    @Column(name = "output_json", length = 12000)
    private String outputJson;

    /** 联合推断报告 JSON：受保护单元可行集、窄化点数、阻断原因 */
    @Column(name = "review_json", nullable = false, length = 16000)
    private String reviewJson;

    /** PENDING_REVIEW / ALLOWED / BLOCKED / SUPPRESSED_ONLY */
    @Column(nullable = false)
    private String status;

    @Column(name = "risk_points_used", nullable = false)
    private int riskPointsUsed;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long v) { this.spaceId = v; }
    public Long getApprovalId() { return approvalId; }
    public void setApprovalId(Long v) { this.approvalId = v; }
    public Long getAnalysisVersionId() { return analysisVersionId; }
    public void setAnalysisVersionId(Long v) { this.analysisVersionId = v; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long v) { this.sessionId = v; }
    public String getRoute() { return route; }
    public void setRoute(String v) { this.route = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public String getCanonicalQueryJson() { return canonicalQueryJson; }
    public void setCanonicalQueryJson(String v) { this.canonicalQueryJson = v; }
    public String getQueryHash() { return queryHash; }
    public void setQueryHash(String v) { this.queryHash = v; }
    public String getRawResultJson() { return rawResultJson; }
    public void setRawResultJson(String v) { this.rawResultJson = v; }
    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String v) { this.outputJson = v; }
    public String getReviewJson() { return reviewJson; }
    public void setReviewJson(String v) { this.reviewJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getRiskPointsUsed() { return riskPointsUsed; }
    public void setRiskPointsUsed(int v) { this.riskPointsUsed = v; }
    public long getCreatedAt() { return createdAt; }
}
