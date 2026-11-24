package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 交付物：冻结结果、算法、审批与审查证明。
 * 撤回/到期后旧交付物仍按原留存许可供审计（FROZEN 只读），但不再开放新查询。
 */
@Entity
@Table(name = "deliverable", indexes = @Index(name = "idx_deliverable_version", columnList = "analysis_version_id"))
public class Deliverable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "analysis_version_id", nullable = false)
    private Long analysisVersionId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(nullable = false)
    private String title;

    /** TABLE / CHART / MODEL_SUMMARY / FIXED_TABLE_SET */
    @Column(nullable = false)
    private String kind;

    /** 冻结时的完整结果载荷（已脱敏输出） */
    @Column(name = "frozen_payload_json", nullable = false, length = 16000)
    private String frozenPayloadJson;

    /** 冻结清单：批准快照、水线、算法版本、规范化查询、约束摘要、审查证明 */
    @Column(name = "manifest_json", nullable = false, length = 20000)
    private String manifestJson;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    /** FROZEN（可审计留存）/ SUPERSEDED（被新版本取代但披露成本保留） */
    @Column(nullable = false)
    private String status = "FROZEN";

    @Column(name = "retention_note", length = 2000)
    private String retentionNote;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long v) { this.spaceId = v; }
    public Long getApprovalId() { return approvalId; }
    public void setApprovalId(Long v) { this.approvalId = v; }
    public Long getAnalysisVersionId() { return analysisVersionId; }
    public void setAnalysisVersionId(Long v) { this.analysisVersionId = v; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long v) { this.attemptId = v; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFrozenPayloadJson() { return frozenPayloadJson; }
    public void setFrozenPayloadJson(String v) { this.frozenPayloadJson = v; }
    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String v) { this.manifestJson = v; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String v) { this.payloadSha256 = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRetentionNote() { return retentionNote; }
    public void setRetentionNote(String v) { this.retentionNote = v; }
    public long getCreatedAt() { return createdAt; }
}
