package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.CollectionPlace;
import com.emirrkls.phokarta.backend.domain.entity.CollectionPlaceId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CollectionPlaceRepository
        extends JpaRepository<CollectionPlace, CollectionPlaceId> {
    @EntityGraph(attributePaths = {"place"})
    List<CollectionPlace> findByCollectionIdOrderByDisplayOrder(UUID collectionId);

    @Query("select coalesce(max(cp.displayOrder), -1) from CollectionPlace cp where cp.collection.id = :id")
    int maxDisplayOrder(@Param("id") UUID collectionId);

    interface CollectionCount {
        UUID getCollectionId();
        long getPlaceCount();
    }

    @Query("""
            select cp.collection.id as collectionId, count(cp) as placeCount
            from CollectionPlace cp where cp.collection.id in :ids group by cp.collection.id
            """)
    List<CollectionCount> countByCollectionIds(@Param("ids") List<UUID> collectionIds);
}
