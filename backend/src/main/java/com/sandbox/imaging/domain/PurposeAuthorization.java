package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 用途授权快照：把批准时的研究目的、模型用途、字段范围与有效期固化为不可变快照。
 * 即使批准后来被撤回/到期，复现期限内链接版本仍以该快照为准；快照缺失即证据不完整，
 * 不允许据此改变成员。
 */
@Entity
@Table(name = "purpose_authorization")
public class PurposeAuthorization {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "approval_code", nullable = false, length = 64)
    private String approvalCode;

    @Column(nullable = false, length = 2000)
    private String purpose;

    @Column(name = "model_usage", length = 2000)
    private String modelUsage;

    @Column(length = 200)
    private String center;

    @Column(name = "spec_hash", nullable = false, length = 64)
    private String specHash;

    @Column(name = "valid_from", nullable = false)
    private long validFrom;

    @Column(name = "valid_to", nullable = false)
    private long validTo;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text")
    private String snapshotJson;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    /** 用途作用域：由批准规格哈希 + 快照哈希 + 目的派生，令牌只在该作用域内可关联 */
    @Column(name = "purpose_scope", length = 200)
    private String purposeScope;

    /** ACTIVE / MISSING / REVOKED */
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getApprovalId() { return approvalId; }
    public void setApprovalId(Long v) { this.approvalId = v; }
    public String getApprovalCode() { return approvalCode; }
    public void setApprovalCode(String v) { this.approvalCode = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public String getModelUsage() { return modelUsage; }
    public void setModelUsage(String v) { this.modelUsage = v; }
    public String getCenter() { return center; }
    public void setCenter(String v) { this.center = v; }
    public String getSpecHash() { return specHash; }
    public void setSpecHash(String v) { this.specHash = v; }
    public long getValidFrom() { return validFrom; }
    public void setValidFrom(long v) { this.validFrom = v; }
    public long getValidTo() { return validTo; }
    public void setValidTo(long v) { this.validTo = v; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String v) { this.snapshotJson = v; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String v) { this.snapshotHash = v; }
    public String getPurposeScope() { return purposeScope; }
    public void setPurposeScope(String v) { this.purposeScope = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
}
