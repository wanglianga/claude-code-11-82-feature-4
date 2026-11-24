package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 短期匹配封条（托管路线）：密钥托管加密的逐例成员—令牌对应密文。
 * 重放快，但托管面较大；到期证明签发时必须销毁密文，只留下不可逆统计摘要。
 */
@Entity
@Table(name = "link_match_seal", indexes = {
        @Index(name = "idx_match_seal_version", columnList = "link_version_id")
})
public class LinkMatchSeal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_version_id", nullable = false)
    private Long linkVersionId;

    @Column(name = "escrow_kid", nullable = false, length = 64)
    private String escrowKid;

    /** 托管加密后的逐例密文载荷（Base64）；到期或未采用托管路线时为 null */
    @Column(name = "sealed_payload", columnDefinition = "text")
    private String sealedPayload;

    /** 封条摘要：到期后保留的不可逆证明 */
    @Column(name = "seal_digest", nullable = false, length = 64)
    private String sealDigest;

    @Column(name = "sealed_at", nullable = false)
    private long sealedAt;

    @Column(name = "destroyed_at")
    private Long destroyedAt;

    /** SEALED / DESTROYED */
    @Column(nullable = false, length = 16)
    private String status = "SEALED";

    public LinkMatchSeal() {}

    public LinkMatchSeal(Long linkVersionId, String escrowKid, String sealedPayload, String sealDigest) {
        this.linkVersionId = linkVersionId;
        this.escrowKid = escrowKid;
        this.sealedPayload = sealedPayload;
        this.sealDigest = sealDigest;
        this.sealedAt = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public Long getLinkVersionId() { return linkVersionId; }
    public String getEscrowKid() { return escrowKid; }
    public String getSealedPayload() { return sealedPayload; }
    public void setSealedPayload(String v) { this.sealedPayload = v; }
    public String getSealDigest() { return sealDigest; }
    public long getSealedAt() { return sealedAt; }
    public Long getDestroyedAt() { return destroyedAt; }
    public void setDestroyedAt(Long v) { this.destroyedAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
