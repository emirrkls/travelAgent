package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.Place;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlaceRepository extends JpaRepository<Place, UUID> {
    interface SummaryRow {
        UUID getId();
        String getName();
        String getCategory();
        String getCoverImage();
        String getCity();
        String getRegion();
        String getCountry();
        double getLatitude();
        double getLongitude();
        int getPriceLevel();
        Double getAverageScore();
        long getRatingCount();
    }

    interface DistanceRow extends SummaryRow {
        double getDistanceMeters();
    }

    interface RatingAggregate {
        UUID getId();
        Double getAverageScore();
        long getRatingCount();
    }

    /**
     * Place discovery list/search. averageScore / ratingCount / minRating / rating sorts
     * use PUBLIC Visit ratings only (Community semantics).
     */
    @Query(value = """
            SELECT p.id, p.name, p.category, p.cover_image AS "coverImage",
                   p.city, p.region, p.country, ST_Y(p.location) AS latitude,
                   ST_X(p.location) AS longitude, p.price_level AS "priceLevel",
                   AVG(v.overall_rating) AS "averageScore", COUNT(v.id) AS "ratingCount"
            FROM places p
            LEFT JOIN visits v ON v.place_id = p.id AND v.visibility = 'PUBLIC'
            WHERE (CAST(:category AS varchar) IS NULL OR p.category = CAST(:category AS varchar))
              AND (CAST(:city AS varchar) IS NULL OR LOWER(p.city) = LOWER(CAST(:city AS varchar)))
              AND (CAST(:search AS varchar) IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%'))
                   OR LOWER(p.description) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')))
            GROUP BY p.id
            HAVING (CAST(:minRating AS double precision) IS NULL
                    OR COALESCE(AVG(v.overall_rating), -1) >= CAST(:minRating AS double precision))
            ORDER BY
              CASE WHEN :sort = 'name,asc' THEN LOWER(p.name) END ASC,
              CASE WHEN :sort = 'name,desc' THEN LOWER(p.name) END DESC,
              CASE WHEN :sort = 'createdAt,asc' THEN p.created_at END ASC,
              CASE WHEN :sort = 'createdAt,desc' THEN p.created_at END DESC,
              CASE WHEN :sort = 'averageScore,asc' THEN AVG(v.overall_rating) END ASC NULLS LAST,
              CASE WHEN :sort = 'averageScore,desc' THEN AVG(v.overall_rating) END DESC NULLS LAST,
              CASE WHEN :sort = 'ratingCount,asc' THEN COUNT(v.id) END ASC,
              CASE WHEN :sort = 'ratingCount,desc' THEN COUNT(v.id) END DESC,
              p.id ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM places p
            WHERE (CAST(:category AS varchar) IS NULL OR p.category = CAST(:category AS varchar))
              AND (CAST(:city AS varchar) IS NULL OR LOWER(p.city) = LOWER(CAST(:city AS varchar)))
              AND (CAST(:search AS varchar) IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%'))
                   OR LOWER(p.description) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')))
              AND (CAST(:minRating AS double precision) IS NULL OR COALESCE(
                    (SELECT AVG(v.overall_rating) FROM visits v
                     WHERE v.place_id = p.id AND v.visibility = 'PUBLIC'), -1)
                    >= CAST(:minRating AS double precision))
            """, nativeQuery = true)
    Page<SummaryRow> search(@Param("category") String category, @Param("city") String city,
                            @Param("search") String search, @Param("minRating") Double minRating,
                            @Param("sort") String sort, Pageable pageable);

    @Query(value = """
            SELECT p.id, p.name, p.category, p.cover_image AS "coverImage",
                   p.city, p.region, p.country, ST_Y(p.location) AS latitude,
                   ST_X(p.location) AS longitude, p.price_level AS "priceLevel",
                   AVG(v.overall_rating) AS "averageScore", COUNT(v.id) AS "ratingCount",
                   ST_Distance(p.location::geography,
                     ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS "distanceMeters"
            FROM places p
            LEFT JOIN visits v ON v.place_id = p.id AND v.visibility = 'PUBLIC'
            WHERE ST_DWithin(p.location::geography,
                     ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius)
              AND (CAST(:category AS varchar) IS NULL OR p.category = CAST(:category AS varchar))
            GROUP BY p.id
            HAVING (CAST(:minRating AS double precision) IS NULL
                    OR COALESCE(AVG(v.overall_rating), -1) >= CAST(:minRating AS double precision))
            ORDER BY "distanceMeters" ASC, p.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<DistanceRow> findNearby(@Param("lat") double latitude, @Param("lon") double longitude,
                                 @Param("radius") double radiusMeters,
                                 @Param("category") String category,
                                 @Param("minRating") Double minRating, @Param("limit") int limit);

    @Query(value = """
            SELECT p.id, p.name, p.category, p.cover_image AS "coverImage",
                   p.city, p.region, p.country, ST_Y(p.location) AS latitude,
                   ST_X(p.location) AS longitude, p.price_level AS "priceLevel",
                   AVG(v.overall_rating) AS "averageScore", COUNT(v.id) AS "ratingCount"
            FROM places p
            LEFT JOIN visits v ON v.place_id = p.id AND v.visibility = 'PUBLIC'
            WHERE p.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326)
              AND ST_Intersects(p.location, ST_MakeEnvelope(:west, :south, :east, :north, 4326))
              AND (CAST(:category AS varchar) IS NULL OR p.category = CAST(:category AS varchar))
            GROUP BY p.id
            HAVING (CAST(:minRating AS double precision) IS NULL
                    OR COALESCE(AVG(v.overall_rating), -1) >= CAST(:minRating AS double precision))
            ORDER BY AVG(v.overall_rating) DESC NULLS LAST, p.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<SummaryRow> findInBounds(@Param("west") double west, @Param("south") double south,
                                  @Param("east") double east, @Param("north") double north,
                                  @Param("category") String category,
                                  @Param("minRating") Double minRating, @Param("limit") int limit);

    /** Batch Community aggregates for embedded place summaries (PUBLIC ratings only). */
    @Query(value = """
            SELECT p.id, AVG(v.overall_rating) AS "averageScore", COUNT(v.id) AS "ratingCount"
            FROM places p
            LEFT JOIN visits v ON v.place_id = p.id AND v.visibility = 'PUBLIC'
            WHERE p.id IN :ids GROUP BY p.id
            """, nativeQuery = true)
    List<RatingAggregate> aggregateByIds(@Param("ids") List<UUID> ids);
}
