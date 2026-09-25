package com.emirrkls.phokarta.backend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class PlaceGraphProtectionRepository {
    private final JdbcTemplate jdbc;

    public PlaceGraphProtectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Visits cover Experiences and their planned, Collection and conversation descendants.
     * The remaining predicates protect direct Place saves, Place Collections and durable
     * acknowledgement anchors.
     */
    public boolean hasPhokartaOwnedGraph(UUID placeId) {
        Boolean protectedPlace = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM visits WHERE place_id = ?)
                    OR EXISTS (SELECT 1 FROM saved_places WHERE place_id = ?)
                    OR EXISTS (SELECT 1 FROM collection_places WHERE place_id = ?)
                    OR EXISTS (SELECT 1 FROM experience_acknowledgements WHERE place_id = ?)
                """, Boolean.class, placeId, placeId, placeId, placeId);
        return Boolean.TRUE.equals(protectedPlace);
    }
}
