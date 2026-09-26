package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Deterministic before/after catalog audit used by the autonomous canary gate. */
@Service
public class PlacePilotCatalogAnomalyService {
    static final double DIDIM_LATITUDE = 37.3751;
    static final double DIDIM_LONGITUDE = 27.2678;
    static final double DIDIM_RADIUS_METERS = 6000.0;
    static final long DENSE_CELL_THRESHOLD = 25;
    private static final String VALID_CATEGORIES =
            "'BEACH','RESTAURANT','CAFE','HOTEL','BAR','NIGHTLIFE',"
                    + "'ATTRACTION','ACTIVITY','NATURE'";

    private final JdbcTemplate jdbc;

    public PlacePilotCatalogAnomalyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CatalogSnapshot captureDidimBaseline() {
        long activePlaces = scalar("""
                SELECT count(*) FROM places
                 WHERE catalog_status = 'ACTIVE'
                   AND ST_DWithin(location::geography,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                """, DIDIM_LONGITUDE, DIDIM_LATITUDE, DIDIM_RADIUS_METERS);
        long sameCoordinateClusters = scalar("""
                SELECT count(*) FROM (
                    SELECT ST_X(location), ST_Y(location)
                      FROM places
                     WHERE catalog_status = 'ACTIVE'
                       AND ST_DWithin(location::geography,
                           ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                     GROUP BY ST_X(location), ST_Y(location) HAVING count(*) > 1
                ) clusters
                """, DIDIM_LONGITUDE, DIDIM_LATITUDE, DIDIM_RADIUS_METERS);
        long sameCoordinateExcess = scalar("""
                SELECT COALESCE(sum(cluster_size - 1), 0) FROM (
                    SELECT count(*) AS cluster_size
                      FROM places
                     WHERE catalog_status = 'ACTIVE'
                       AND ST_DWithin(location::geography,
                           ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                     GROUP BY ST_X(location), ST_Y(location) HAVING count(*) > 1
                ) clusters
                """, DIDIM_LONGITUDE, DIDIM_LATITUDE, DIDIM_RADIUS_METERS);
        long sameNameCategoryNearbyPairs = scalar("""
                SELECT count(*)
                  FROM places first_place
                  JOIN places second_place ON first_place.id < second_place.id
                 WHERE first_place.catalog_status = 'ACTIVE'
                   AND second_place.catalog_status = 'ACTIVE'
                   AND first_place.category = second_place.category
                   AND lower(regexp_replace(trim(first_place.name), '\\s+', ' ', 'g')) =
                       lower(regexp_replace(trim(second_place.name), '\\s+', ' ', 'g'))
                   AND ST_DWithin(first_place.location::geography,
                       second_place.location::geography, 75)
                   AND ST_DWithin(first_place.location::geography,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                   AND ST_DWithin(second_place.location::geography,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                """, DIDIM_LONGITUDE, DIDIM_LATITUDE, DIDIM_RADIUS_METERS,
                DIDIM_LONGITUDE, DIDIM_LATITUDE, DIDIM_RADIUS_METERS);
        DenseCells dense = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE cell_size > ?) AS dense_cells,
                       COALESCE(max(cell_size), 0) AS maximum_cell_size,
                       COALESCE(sum(GREATEST(cell_size - ?, 0)), 0) AS dense_excess
                  FROM (
                    SELECT count(*) AS cell_size
                      FROM places
                     WHERE catalog_status = 'ACTIVE'
                       AND ST_DWithin(location::geography,
                           ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                     GROUP BY floor((ST_X(location) + 180.0) * 1000.0),
                              floor((ST_Y(location) + 90.0) * 1000.0)
                  ) cells
                """, (rs, rowNum) -> new DenseCells(
                rs.getLong("dense_cells"), rs.getLong("maximum_cell_size"),
                rs.getLong("dense_excess")), DENSE_CELL_THRESHOLD, DENSE_CELL_THRESHOLD,
                DIDIM_LONGITUDE, DIDIM_LATITUDE, DIDIM_RADIUS_METERS);
        Map<String, Long> categories = new TreeMap<>();
        jdbc.query("""
                SELECT category, count(*) AS category_count
                  FROM places
                 WHERE catalog_status = 'ACTIVE'
                   AND ST_DWithin(location::geography,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                 GROUP BY category ORDER BY category
                """, (rs, rowNum) -> Map.entry(rs.getString("category"),
                rs.getLong("category_count")), DIDIM_LONGITUDE, DIDIM_LATITUDE,
                DIDIM_RADIUS_METERS).forEach(entry ->
                categories.put(entry.getKey(), entry.getValue()));
        return new CatalogSnapshot(activePlaces, sameCoordinateClusters,
                sameCoordinateExcess, sameNameCategoryNearbyPairs,
                dense == null ? 0 : dense.count(), dense == null ? 0 : dense.maximumSize(),
                dense == null ? 0 : dense.excess(), Map.copyOf(categories));
    }

    public AuditResult audit(UUID syncRunId, CatalogSnapshot before) {
        CatalogSnapshot after = captureDidimBaseline();
        long newSameCoordinateAnomalies = positiveDelta(
                after.sameCoordinateExcess(), before.sameCoordinateExcess());
        long newNearDuplicatePairs = positiveDelta(
                after.sameNameCategoryNearbyPairs(), before.sameNameCategoryNearbyPairs());
        long newDenseMarkerExcess = positiveDelta(
                after.denseMarkerExcess(), before.denseMarkerExcess());
        Map<String, Long> categoryGrowth = categoryGrowth(before, after);
        long newPlaces = categoryGrowth.values().stream().mapToLong(Long::longValue).sum();
        long categorySpikes = categoryGrowth.values().stream()
                .filter(growth -> newPlaces >= 20 && growth >= 20
                        && ((double) growth / newPlaces) > 0.90)
                .count();

        long invalidOrOutOfScopeCoordinates = scalar("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                  JOIN places place ON place.id = write.place_id
                 WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                   AND (place.location IS NULL OR NOT ST_IsValid(place.location)
                        OR ST_X(place.location) NOT BETWEEN -180 AND 180
                        OR ST_Y(place.location) NOT BETWEEN -90 AND 90
                        OR NOT ST_DWithin(place.location::geography,
                            ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?))
                """, syncRunId, DIDIM_LONGITUDE, DIDIM_LATITUDE, DIDIM_RADIUS_METERS + 0.01);
        long invalidCategories = scalar("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                  JOIN places place ON place.id = write.place_id
                 WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                   AND place.category NOT IN (%s)
                """.formatted(VALID_CATEGORIES), syncRunId);
        long providerReferenceCollisions = scalar("""
                SELECT count(*) FROM (
                    SELECT provider, external_id FROM place_external_refs
                     GROUP BY provider, external_id HAVING count(*) > 1
                ) collisions
                """);
        long sourceOrphans = scalar("""
                SELECT count(*)
                  FROM place_validation_decisions decision
                  CROSS JOIN LATERAL unnest(decision.source_record_ids) ids(source_id)
                  LEFT JOIN place_source_records source ON source.id = ids.source_id
                 WHERE decision.sync_run_id = ? AND source.id IS NULL
                """, syncRunId);
        long canonicalWithoutProvenance = scalar("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                 WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                   AND NOT EXISTS (
                       SELECT 1 FROM place_external_refs ref
                        WHERE ref.place_id = write.place_id
                          AND ref.status IN ('ACTIVE', 'MERGED')
                   )
                """, syncRunId);
        long duplicateCanonicalAssignments = scalar("""
                SELECT count(*) FROM (
                    SELECT canonical_place_id
                      FROM place_validation_decisions
                     WHERE sync_run_id = ? AND selected_for_stage
                     GROUP BY canonical_place_id HAVING count(*) > 1
                ) duplicates
                """, syncRunId);
        long failures = newSameCoordinateAnomalies + newNearDuplicatePairs
                + newDenseMarkerExcess + categorySpikes + invalidOrOutOfScopeCoordinates
                + invalidCategories + providerReferenceCollisions + sourceOrphans
                + canonicalWithoutProvenance + duplicateCanonicalAssignments;

        ObjectNode report = JsonNodeFactory.instance.objectNode();
        report.put("version", "didim-catalog-anomaly-v1");
        report.set("before", before.toJson());
        report.set("after", after.toJson());
        ObjectNode deltas = report.putObject("deltas");
        deltas.put("same_coordinate_anomalies", newSameCoordinateAnomalies);
        deltas.put("same_name_category_near_duplicate_pairs", newNearDuplicatePairs);
        deltas.put("dense_marker_excess", newDenseMarkerExcess);
        deltas.put("category_spikes", categorySpikes);
        ObjectNode categoryGrowthJson = deltas.putObject("category_growth");
        categoryGrowth.forEach(categoryGrowthJson::put);
        report.put("invalid_or_out_of_scope_coordinates",
                invalidOrOutOfScopeCoordinates);
        report.put("invalid_categories", invalidCategories);
        report.put("provider_reference_collisions", providerReferenceCollisions);
        report.put("source_orphans", sourceOrphans);
        report.put("canonical_without_provenance", canonicalWithoutProvenance);
        report.put("duplicate_canonical_assignments", duplicateCanonicalAssignments);
        report.put("passed", failures == 0);
        return new AuditResult(report, failures == 0, newSameCoordinateAnomalies,
                newNearDuplicatePairs, duplicateCanonicalAssignments);
    }

    private Map<String, Long> categoryGrowth(
            CatalogSnapshot before,
            CatalogSnapshot after
    ) {
        Map<String, Long> result = new LinkedHashMap<>();
        after.categoryCounts().keySet().stream().sorted().forEach(category -> {
            long growth = after.categoryCounts().getOrDefault(category, 0L)
                    - before.categoryCounts().getOrDefault(category, 0L);
            if (growth > 0) result.put(category, growth);
        });
        return result;
    }

    private long positiveDelta(long after, long before) {
        return Math.max(0, after - before);
    }

    private long scalar(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private record DenseCells(long count, long maximumSize, long excess) {}

    public record CatalogSnapshot(
            long activePlaces,
            long sameCoordinateClusters,
            long sameCoordinateExcess,
            long sameNameCategoryNearbyPairs,
            long denseMarkerCells,
            long maximumMarkerCellSize,
            long denseMarkerExcess,
            Map<String, Long> categoryCounts
    ) {
        public ObjectNode toJson() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("active_places", activePlaces);
            value.put("same_coordinate_clusters", sameCoordinateClusters);
            value.put("same_coordinate_excess", sameCoordinateExcess);
            value.put("same_name_category_near_duplicate_pairs",
                    sameNameCategoryNearbyPairs);
            value.put("dense_marker_cells", denseMarkerCells);
            value.put("maximum_marker_cell_size", maximumMarkerCellSize);
            value.put("dense_marker_excess", denseMarkerExcess);
            ObjectNode categories = value.putObject("category_counts");
            categoryCounts.forEach(categories::put);
            return value;
        }
    }

    public record AuditResult(
            ObjectNode report,
            boolean passed,
            long sameCoordinateAnomalies,
            long nearDuplicatePairs,
            long duplicateCanonicalAssignments
    ) {}
}
