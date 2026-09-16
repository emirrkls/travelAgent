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

    interface V2DimensionAggregateRow {
        String getDimensionKey();
        long getContributionCount();
        Double getNumericAverage();
        long getLegacyNumericContributionCount();
    }

    @Query(value = """
            select score.dimension_key as "dimensionKey",
                   count(*) as "contributionCount",
                   avg(score.score) as "numericAverage",
                   count(*) filter (where score.semantic_state_code is null)
                       as "legacyNumericContributionCount"
            from visit_dimension_scores score
            join visits visit on visit.id = score.visit_id
            where visit.place_id = :placeId
              and visit.visibility in ('PUBLIC', 'FRIENDS')
            group by score.dimension_key
            order by score.dimension_key
            """, nativeQuery = true)
    List<V2DimensionAggregateRow> aggregateV2ForPlace(@Param("placeId") UUID placeId);

    interface SemanticStateAggregateRow {
        String getDimensionKey();
        String getSemanticStateCode();
        long getContributionCount();
    }

    @Query(value = """
            select score.dimension_key as "dimensionKey",
                   score.semantic_state_code as "semanticStateCode",
                   count(*) as "contributionCount"
            from visit_dimension_scores score
            join visits visit on visit.id = score.visit_id
            where visit.place_id = :placeId
              and visit.visibility in ('PUBLIC', 'FRIENDS')
              and score.semantic_state_code is not null
            group by score.dimension_key, score.semantic_state_code
            order by score.dimension_key, score.semantic_state_code
            """, nativeQuery = true)
    List<SemanticStateAggregateRow> aggregateSemanticStatesForPlace(@Param("placeId") UUID placeId);
}
