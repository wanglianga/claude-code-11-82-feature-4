package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.LinkExpiryCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LinkExpiryCertificateRepository extends JpaRepository<LinkExpiryCertificate, Long> {
    Optional<LinkExpiryCertificate> findByLinkVersionId(Long linkVersionId);
    List<LinkExpiryCertificate> findAllByOrderByIdDesc();
}
