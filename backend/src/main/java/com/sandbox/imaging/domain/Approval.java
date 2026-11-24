package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 数据批准：明确中心、研究目的、字段、入组期间、允许队列定义、模型用途和到期时点。
 * 批准编译器把请求规格编译为 {@link #compiledJson}（确定性 JSON）并记录哈希。
 */
@Entity
@Table(name = "approval")
public class Approval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "space_id")
    private ResearchSpace space;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String center;

    /** DRAFT / ACTIVE / REVOKED / EXPIRED */
    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "valid_from", nullable = false)
    private long validFrom;

    @Column(name = "valid_to", nullable = false)
    private long validTo;

    /** 原始批准请求（字段、入组期间、允许队列定义、模型用途等） */
    @Column(name = "request_json", nullable = false, length = 20000)
    private String requestJson;

    /** 编译器产出的确定性规格 */
    @Column(name = "compiled_json", nullable = false, length = 20000)
    private String compiledJson;

    @Column(name = "spec_hash", nullable = false, length = 64)
    private String specHash;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "revoked_at")
    private Long revokedAt;

    @Column(name = "revoke_reason", length = 2000)
    private String revokeReason;

    public Long getId() { return id; }
    public ResearchSpace getSpace() { return space; }
    public void setSpace(ResearchSpace space) { this.space = space; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getCenter() { return center; }
    public void setCenter(String center) { this.center = center; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getValidFrom() { return validFrom; }
    public void setValidFrom(long v) { this.validFrom = v; }
    public long getValidTo() { return validTo; }
    public void setValidTo(long v) { this.validTo = v; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String v) { this.requestJson = v; }
    public String getCompiledJson() { return compiledJson; }
    public void setCompiledJson(String v) { this.compiledJson = v; }
    public String getSpecHash() { return specHash; }
    public void setSpecHash(String v) { this.specHash = v; }
    public long getCreatedAt() { return createdAt; }
    public Long getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Long revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
}
