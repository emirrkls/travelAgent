package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.SavedPlace;
import com.emirrkls.phokarta.backend.domain.entity.SavedPlaceId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, SavedPlaceId> {
    @EntityGraph(attributePaths = {"place"})
    Page<SavedPlace> findByUserIdOrderBySavedAtDesc(UUID userId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into saved_places (user_id, place_id, saved_at)
            values (:userId, :placeId, :savedAt)
            on conflict (user_id, place_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("placeId") UUID placeId,
                       @Param("savedAt") OffsetDateTime savedAt);

    @EntityGraph(attributePaths = {"place"})
    @Query("select sp from SavedPlace sp where sp.id = :id")
    Optional<SavedPlace> findDetailedById(@Param("id") SavedPlaceId id);
}
