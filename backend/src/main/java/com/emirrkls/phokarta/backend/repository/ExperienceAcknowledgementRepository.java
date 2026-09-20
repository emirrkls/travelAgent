package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.ExperienceAcknowledgement;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface ExperienceAcknowledgementRepository extends JpaRepository<ExperienceAcknowledgement, UUID> {
    @EntityGraph(attributePaths = {"user", "sourceExperience", "sourceExperience.user", "sourceExperience.place", "place", "convertedExperience"})
    Optional<ExperienceAcknowledgement> findByUserIdAndSourceExperienceId(UUID userId, UUID sourceExperienceId);
    boolean existsByUserIdAndSourceExperienceId(UUID userId, UUID sourceExperienceId);
    long countBySourceExperienceId(UUID sourceExperienceId);
    @EntityGraph(attributePaths = {"user", "sourceExperience", "sourceExperience.user", "sourceExperience.place", "place"})
    Page<ExperienceAcknowledgement> findByUserIdAndConvertedAtIsNullOrderByAcknowledgedAtDescIdDesc(
            UUID userId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "sourceExperience", "place", "convertedExperience"})
    @Query("select value from ExperienceAcknowledgement value where value.id = :id")
    Optional<ExperienceAcknowledgement> findByIdForUpdate(@Param("id") UUID id);
}
