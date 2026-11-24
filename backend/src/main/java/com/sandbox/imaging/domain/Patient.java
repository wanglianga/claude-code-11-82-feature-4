package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/** 脱敏患者：只存伪键与结构化属性，不存原始影像或患者级身份 */
@Entity
@Table(name = "patient")
public class Patient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_key", nullable = false, unique = true, length = 64)
    private String patientKey;

    @Column(nullable = false)
    private String hospital;

    @Column(name = "age_band", length = 16)
    private String ageBand;

    @Column(length = 16)
    private String sex;

    public Patient() {}

    public Patient(String patientKey, String hospital, String ageBand, String sex) {
        this.patientKey = patientKey;
        this.hospital = hospital;
        this.ageBand = ageBand;
        this.sex = sex;
    }

    public Long getId() { return id; }
    public String getPatientKey() { return patientKey; }
    public String getHospital() { return hospital; }
    public String getAgeBand() { return ageBand; }
    public String getSex() { return sex; }
}
