package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 分析版本：研究目的或数据水位改变必须新建版本；特征算法升级也只生成新版本，
 * 不改写已发布结论、不抹去其披露成本。
 */
@Entity
@Table(name = "analysis_version")
public class AnalysisVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "approval_id")
    private Approval approval;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false)
    private String purpose;

    /** 该版本绑定的数据水线标签 */
    @Column(name = "waterline_label", nullable = false)
    private String waterlineLabel;

    /** 绑定的特征算法版本 */
    @Column(name = "algorithm_version", nullable = false)
    private String algorithmVersion;

    @Column(name = "version_spec_json", nullable = false, length = 10000)
    private String versionSpecJson;

    @Column(name = "spec_hash", nullable = false, length = 64)
    private String specHash;

    /** ACTIVE / CLOSED */
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Approval getApproval() { return approval; }
    public void setApproval(Approval approval) { this.approval = approval; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getWaterlineLabel() { return waterlineLabel; }
    public void setWaterlineLabel(String waterlineLabel) { this.waterlineLabel = waterlineLabel; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String algorithmVersion) { this.algorithmVersion = algorithmVersion; }
    public String getVersionSpecJson() { return versionSpecJson; }
    public void setVersionSpecJson(String v) { this.versionSpecJson = v; }
    public String getSpecHash() { return specHash; }
    public void setSpecHash(String specHash) { this.specHash = specHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
}
