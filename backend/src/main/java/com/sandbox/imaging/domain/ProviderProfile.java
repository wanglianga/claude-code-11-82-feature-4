package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 院外提供方（例如某医院影像库）。受控重建若走“提供方重建”路线，
 * 必须依赖该提供方在复现期限内持续可调用；提供方不可用即证据不完整。
 */
@Entity
@Table(name = "provider_profile")
public class ProviderProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_code", nullable = false, unique = true)
    private String providerCode;

    @Column(nullable = false)
    private String name;

    /** AVAILABLE / UNAVAILABLE */
    @Column(nullable = false)
    private String status = "AVAILABLE";

    /** 该提供方覆盖的院区（用于生成其输入批次候选行）；null 表示联合清算方 */
    @Column(name = "covers_hospital", length = 16)
    private String coversHospital;

    @Column(length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public ProviderProfile() {}

    public ProviderProfile(String providerCode, String name, String note) {
        this.providerCode = providerCode;
        this.name = name;
        this.note = note;
    }

    public Long getId() { return id; }
    public String getProviderCode() { return providerCode; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCoversHospital() { return coversHospital; }
    public void setCoversHospital(String v) { this.coversHospital = v; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public long getCreatedAt() { return createdAt; }
}
