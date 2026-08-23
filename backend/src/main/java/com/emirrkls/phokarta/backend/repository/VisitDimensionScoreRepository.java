package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScore;
import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScoreId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VisitDimensionScoreRepository
        extends JpaRepository<VisitDimensionScore, VisitDimensionScoreId> {
    List<VisitDimensionScore> findByIdVisitId(UUID visitId);
    List<VisitDimensionScore> findByIdVisitIdIn(List<UUID> visitIds);

    interface DimensionAggregate {
        String getDimensionKey();
        double getAverage();
    }

    /**
     * Community dimension averages from PUBLIC Visits only.
     */
    @Query("""
            select s.id.dimensionKey as dimensionKey, avg(s.score) as average
            from VisitDimensionScore s
            where s.visit.place.id = :placeId
              and s.visit.visibility =
                  com.emirrkls.phokarta.backend.domain.model.Visibility.PUBLIC
            group by s.id.dimensionKey
            order by s.id.dimensionKey
            """)
    List<DimensionAggregate> aggregateForPlace(@Param("placeId") UUID placeId);
}
