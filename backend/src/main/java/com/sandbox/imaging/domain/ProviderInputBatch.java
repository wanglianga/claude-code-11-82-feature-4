package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 提供方输入批次：封存当时的院外源行快照（仅伪引用与候选阻断所需属性，无原始身份）。
 * 复现期限内保留；到期证明签发时若没有其他有效链接版本共用该批次，则清空逐例条目。
 */
@Entity
@Table(name = "provider_input_batch", indexes = {
        @Index(name = "idx_batch_provider", columnList = "provider_code")
})
public class ProviderInputBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    /** 用途作用域：链接用途 + 授权快照的派生子串，令牌按该作用域绑定 */
    @Column(name = "purpose_scope", nullable = false, length = 200)
    private String purposeScope;

    /** 批次条目 JSON：[{localRef,hospital,sex,ageBand}]，按 localRef 排序 */
    @Column(name = "entries_json", nullable = false, columnDefinition = "text")
    private String entriesJson;

    @Column(name = "batch_hash", nullable = false, length = 64)
    private String batchHash;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    /** SEALED / PURGED / MISSING */
    @Column(nullable = false)
    private String status = "SEALED";

    @Column(name = "produced_at", nullable = false)
    private long producedAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public String getPurposeScope() { return purposeScope; }
    public void setPurposeScope(String v) { this.purposeScope = v; }
    public String getEntriesJson() { return entriesJson; }
    public void setEntriesJson(String v) { this.entriesJson = v; }
    public String getBatchHash() { return batchHash; }
    public void setBatchHash(String v) { this.batchHash = v; }
    public int getEntryCount() { return entryCount; }
    public void setEntryCount(int v) { this.entryCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getProducedAt() { return producedAt; }
    public void setProducedAt(long v) { this.producedAt = v; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long v) { this.expiresAt = v; }
    public long getCreatedAt() { return createdAt; }
}
