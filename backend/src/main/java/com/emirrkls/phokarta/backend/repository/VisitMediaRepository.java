package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.VisitMedia;
import com.emirrkls.phokarta.backend.domain.entity.VisitMediaId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitMediaRepository extends JpaRepository<VisitMedia, VisitMediaId> {
    @EntityGraph(attributePaths = {"media", "media.owner", "visit", "visit.user"})
    @Query("select vm from VisitMedia vm where vm.media.id = :mediaId")
    Optional<VisitMedia> findByMediaId(@Param("mediaId") UUID mediaId);

    @EntityGraph(attributePaths = {"media", "media.owner"})
    @Query("""
            select vm from VisitMedia vm
            where vm.visit.id in :visitIds
            order by vm.visit.id, vm.sortOrder
            """)
    List<VisitMedia> findByVisitIds(@Param("visitIds") List<UUID> visitIds);
}
