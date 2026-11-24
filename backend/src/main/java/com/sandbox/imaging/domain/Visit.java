package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/** 访视：脱敏患者键 + 访视标识 + 随访窗口（天） */
@Entity
@Table(name = "visit")
public class Visit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(name = "visit_key", nullable = false, unique = true, length = 64)
    private String visitKey;

    @Column(name = "visit_date", nullable = false)
    private long visitDate;

    @Column(name = "follow_up_days")
    private Integer followUpDays;

    public Visit() {}

    public Visit(Patient patient, String visitKey, long visitDate, Integer followUpDays) {
        this.patient = patient;
        this.visitKey = visitKey;
        this.visitDate = visitDate;
        this.followUpDays = followUpDays;
    }

    public Long getId() { return id; }
    public Patient getPatient() { return patient; }
    public String getVisitKey() { return visitKey; }
    public long getVisitDate() { return visitDate; }
    public Integer getFollowUpDays() { return followUpDays; }
}
