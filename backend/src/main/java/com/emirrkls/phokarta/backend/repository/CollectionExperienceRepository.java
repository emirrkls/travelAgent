package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.CollectionExperience;
import com.emirrkls.phokarta.backend.domain.entity.CollectionExperienceId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface CollectionExperienceRepository extends JpaRepository<CollectionExperience, CollectionExperienceId> {
    @EntityGraph(attributePaths = {"experience", "experience.user", "experience.place"})
    List<CollectionExperience> findByCollectionIdOrderByDisplayOrder(UUID collectionId);
    @Query("select coalesce(max(value.displayOrder), -1) from CollectionExperience value where value.collection.id = :collectionId")
    int maxDisplayOrder(@Param("collectionId") UUID collectionId);
    @Query("select value.collection.id as collectionId, count(value) as itemCount from CollectionExperience value where value.collection.id in :ids group by value.collection.id")
    List<CountByCollection> countByCollectionIds(@Param("ids") List<UUID> ids);
    interface CountByCollection { UUID getCollectionId(); long getItemCount(); }
}
