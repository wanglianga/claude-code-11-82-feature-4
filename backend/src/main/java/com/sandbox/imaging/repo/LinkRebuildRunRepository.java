package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.LinkRebuildRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LinkRebuildRunRepository extends JpaRepository<LinkRebuildRun, Long> {
    List<LinkRebuildRun> findByLinkVersionIdOrderByIdDesc(Long linkVersionId);
}
