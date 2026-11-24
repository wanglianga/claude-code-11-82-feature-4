package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 到期证明：复现期限届满后签发。销毁逐例恢复能力（托管封条密文、提供方批次逐例条目、重建授权），
 * 只保留不可逆统计审计摘要，并明确记录“不能再逐例复现”。
 */
@Entity
@Table(name = "link_expiry_certificate", indexes = {
        @Index(name = "idx_expiry_version", columnList = "link_version_id")
})
public class LinkExpiryCertificate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "link_version_id", nullable = false)
    private Long linkVersionId;

    @Column(name = "link_version_code", nullable = false, length = 64)
    private String linkVersionCode;

    @Column(name = "window_end", nullable = false)
    private long windowEnd;

    @Column(name = "issued_at", nullable = false)
    private long issuedAt;

    /** 不可逆统计审计摘要（只含数量、类别分布与哈希根，不含逐例可识别映射） */
    @Column(name = "irreversible_digest_json", nullable = false, columnDefinition = "text")
    private String irreversibleDigestJson;

    @Column(name = "digest_root", nullable = false, length = 64)
    private String digestRoot;

    /** 被销毁内容清单（类别与数量，不含值） */
    @Column(name = "purged_manifest_json", nullable = false, columnDefinition = "text")
    private String purgedManifestJson;

    @Column(name = "per_case_recoverable", nullable = false)
    private boolean perCaseRecoverable = false;

    @Column(name = "statement", nullable = false, length = 1000)
    private String statement;

    @Column(name = "seal_attestation_json", nullable = false, columnDefinition = "text")
    private String sealAttestationJson;

    @Column(name = "issued_by", nullable = false)
    private String issuedBy;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getLinkVersionId() { return linkVersionId; }
    public void setLinkVersionId(Long v) { this.linkVersionId = v; }
    public String getLinkVersionCode() { return linkVersionCode; }
    public void setLinkVersionCode(String v) { this.linkVersionCode = v; }
    public long getWindowEnd() { return windowEnd; }
    public void setWindowEnd(long v) { this.windowEnd = v; }
    public long getIssuedAt() { return issuedAt; }
    public void setIssuedAt(long v) { this.issuedAt = v; }
    public String getIrreversibleDigestJson() { return irreversibleDigestJson; }
    public void setIrreversibleDigestJson(String v) { this.irreversibleDigestJson = v; }
    public String getDigestRoot() { return digestRoot; }
    public void setDigestRoot(String v) { this.digestRoot = v; }
    public String getPurgedManifestJson() { return purgedManifestJson; }
    public void setPurgedManifestJson(String v) { this.purgedManifestJson = v; }
    public boolean isPerCaseRecoverable() { return perCaseRecoverable; }
    public void setPerCaseRecoverable(boolean v) { this.perCaseRecoverable = v; }
    public String getStatement() { return statement; }
    public void setStatement(String v) { this.statement = v; }
    public String getSealAttestationJson() { return sealAttestationJson; }
    public void setSealAttestationJson(String v) { this.sealAttestationJson = v; }
    public String getIssuedBy() { return issuedBy; }
    public void setIssuedBy(String v) { this.issuedBy = v; }
}
