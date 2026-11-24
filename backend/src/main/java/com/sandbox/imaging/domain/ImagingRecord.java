package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 影像事实：只保存结构化影像特征（不含原始影像）。
 * feature_version 标记生成该特征的算法版本；算法升级生成新行/新版本，不改写旧行。
 */
@Entity
@Table(name = "imaging_record", indexes = {
        @Index(name = "idx_imaging_patient", columnList = "patient_id"),
        @Index(name = "idx_imaging_date", columnList = "imaging_date")
})
public class ImagingRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @Column(name = "imaging_date", nullable = false)
    private long imagingDate;

    @Column(nullable = false)
    private String modality;

    @Column(name = "body_part")
    private String bodyPart;

    @Column(name = "feature_version", nullable = false)
    private String featureVersion;

    /** 结构化特征 JSON，例如 {"lesion_count":2,"max_mm":18.4,...} */
    @Column(name = "features_json", nullable = false, length = 8000)
    private String featuresJson;

    public ImagingRecord() {}

    public ImagingRecord(Patient patient, Visit visit, long imagingDate, String modality,
                         String bodyPart, String featureVersion, String featuresJson) {
        this.patient = patient;
        this.visit = visit;
        this.imagingDate = imagingDate;
        this.modality = modality;
        this.bodyPart = bodyPart;
        this.featureVersion = featureVersion;
        this.featuresJson = featuresJson;
    }

    public Long getId() { return id; }
    public Patient getPatient() { return patient; }
    public Visit getVisit() { return visit; }
    public long getImagingDate() { return imagingDate; }
    public String getModality() { return modality; }
    public String getBodyPart() { return bodyPart; }
    public String getFeatureVersion() { return featureVersion; }
    public String getFeaturesJson() { return featuresJson; }
}
