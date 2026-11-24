package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.Approval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {
    Optional<Approval> findByCode(String code);
    List<Approval> findBySpaceIdOrderByIdDesc(Long spaceId);
}
