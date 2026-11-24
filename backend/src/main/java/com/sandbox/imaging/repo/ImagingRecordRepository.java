package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.ImagingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImagingRecordRepository extends JpaRepository<ImagingRecord, Long> {
    List<ImagingRecord> findByPatientIdIn(List<Long> patientIds);
    List<ImagingRecord> findByPatientIdInAndFeatureVersion(List<Long> patientIds, String featureVersion);
    List<ImagingRecord> findByFeatureVersion(String featureVersion);
}
