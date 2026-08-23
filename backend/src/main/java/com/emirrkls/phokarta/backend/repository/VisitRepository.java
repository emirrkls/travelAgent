package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VisitRepository extends JpaRepository<Visit, UUID> {
    @EntityGraph(attributePaths = {"place", "user"})
    Page<Visit> findByUserIdOrderByVisitedAtDescCreatedAtDescIdDesc(
            UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "place"})
    Page<Visit> findByPlaceIdAndVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
            UUID placeId, Visibility visibility, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "place"})
    Page<Visit> findByVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
            Visibility visibility, Pageable pageable);

    @Query("""
            select v from Visit v join fetch v.user join fetch v.place
            where v.place.id = :placeId and v.visibility = :visibility
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    List<Visit> findRecent(@Param("placeId") UUID placeId,
                           @Param("visibility") Visibility visibility, Pageable pageable);

    interface ScoreAggregate {
        Double getAverage();
        long getCount();
    }

    @Query("""
            select avg(v.overallRating) as average, count(v) as count
            from Visit v where v.place.id = :placeId
            """)
    ScoreAggregate aggregate(@Param("placeId") UUID placeId);
}
