package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    Optional<Patient> findByPatientKey(String key);
    List<Patient> findByPatientKeyIn(List<String> keys);
}
