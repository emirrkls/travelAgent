package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.PlannedExperience;
import com.emirrkls.phokarta.backend.domain.entity.PlannedExperienceId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PlannedExperienceRepository extends JpaRepository<PlannedExperience, PlannedExperienceId> {
    @EntityGraph(attributePaths = {"experience", "experience.user", "experience.place"})
    Page<PlannedExperience> findByIdUserIdOrderByPlannedAtDesc(UUID userId, Pageable pageable);
    boolean existsByIdUserIdAndIdExperienceId(UUID userId, UUID experienceId);
}
