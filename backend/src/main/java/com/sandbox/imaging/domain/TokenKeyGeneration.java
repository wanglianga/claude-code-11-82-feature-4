package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 令牌化密钥代际。轮换后旧代际转为 RETIRED，但密钥材料仍留在托管面（keyMaterial），
 * 只允许受控重建服务按封存时的旧规则取用；任何对外视图只暴露指纹，不暴露密钥。
 */
@Entity
@Table(name = "token_key_generation")
public class TokenKeyGeneration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String kid;

    /** ACTIVE / RETIRED / DESTROYED */
    @Column(nullable = false)
    private String status = "ACTIVE";

    /** 托管密钥材料（Base64，仅重建服务内部使用，绝不出现在 API 响应中） */
    @Column(name = "key_material", nullable = false, columnDefinition = "text")
    private String keyMaterial;

    /** 密钥指纹：sha256(keyMaterial) 前 16 位，可公开 */
    @Column(nullable = false, length = 32)
    private String fingerprint;

    @Column(name = "escrow_ref", nullable = false, length = 200)
    private String escrowRef;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "retired_at")
    private Long retiredAt;

    @Column(length = 500)
    private String note;

    public TokenKeyGeneration() {}

    public TokenKeyGeneration(String kid, String keyMaterial, String fingerprint, String escrowRef, String note) {
        this.kid = kid;
        this.keyMaterial = keyMaterial;
        this.fingerprint = fingerprint;
        this.escrowRef = escrowRef;
        this.note = note;
    }

    public Long getId() { return id; }
    public String getKid() { return kid; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getKeyMaterial() { return keyMaterial; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String v) { this.fingerprint = v; }
    public String getEscrowRef() { return escrowRef; }
    public long getCreatedAt() { return createdAt; }
    public Long getRetiredAt() { return retiredAt; }
    public void setRetiredAt(Long retiredAt) { this.retiredAt = retiredAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
