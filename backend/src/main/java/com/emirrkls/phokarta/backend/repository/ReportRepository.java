package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.Report;
import com.emirrkls.phokarta.backend.domain.model.ReportStatus;
import com.emirrkls.phokarta.backend.domain.model.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    @Query("""
            select r from Report r
            where r.reporter.id = :reporterId
              and r.targetType = com.emirrkls.phokarta.backend.domain.model.ReportTargetType.USER
              and r.targetUser.id = :targetUserId
              and r.status = com.emirrkls.phokarta.backend.domain.model.ReportStatus.OPEN
            """)
    Optional<Report> findOpenUserReport(@Param("reporterId") UUID reporterId,
                                        @Param("targetUserId") UUID targetUserId);

    @Query("""
            select r from Report r
            where r.reporter.id = :reporterId
              and r.targetType = com.emirrkls.phokarta.backend.domain.model.ReportTargetType.VISIT
              and r.targetVisit.id = :targetVisitId
              and r.status = com.emirrkls.phokarta.backend.domain.model.ReportStatus.OPEN
            """)
    Optional<Report> findOpenVisitReport(@Param("reporterId") UUID reporterId,
                                         @Param("targetVisitId") UUID targetVisitId);

    long countByStatus(ReportStatus status);

    @Query("""
            select case when count(r) > 0 then true else false end from Report r
            where r.id = :id
              and r.targetType = :targetType
              and r.status = com.emirrkls.phokarta.backend.domain.model.ReportStatus.OPEN
            """)
    boolean existsOpenByIdAndTargetType(@Param("id") UUID id,
                                        @Param("targetType") ReportTargetType targetType);
}
