package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 提供方重建授权（重建路线）：复现期限内提供方允许按旧输入、旧规则重新计算成员的受控授权。
 * 到期或提供方不可用时该授权 FAILED/REVOKED，链接版本进入证据不完整，而非允许成员漂移。
 */
@Entity
@Table(name = "provider_rebuild_authorization", indexes = {
        @Index(name = "idx_rebuild_auth_version", columnList = "link_version_id")
})
public class ProviderRebuildAuthorization {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "link_version_id", nullable = false)
    private Long linkVersionId;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "purpose_scope", nullable = false, length = 200)
    private String purposeScope;

    /** ACTIVE / REVOKED / EXPIRED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "max_invocations", nullable = false)
    private int maxInvocations = 20;

    @Column(name = "invocation_count", nullable = false)
    private int invocationCount = 0;

    @Column(name = "authorized_at", nullable = false)
    private long authorizedAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    @Column(length = 500)
    private String note;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getLinkVersionId() { return linkVersionId; }
    public void setLinkVersionId(Long v) { this.linkVersionId = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public String getPurposeScope() { return purposeScope; }
    public void setPurposeScope(String v) { this.purposeScope = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getMaxInvocations() { return maxInvocations; }
    public void setMaxInvocations(int v) { this.maxInvocations = v; }
    public int getInvocationCount() { return invocationCount; }
    public void setInvocationCount(int v) { this.invocationCount = v; }
    public long getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(long v) { this.authorizedAt = v; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long v) { this.expiresAt = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
}
