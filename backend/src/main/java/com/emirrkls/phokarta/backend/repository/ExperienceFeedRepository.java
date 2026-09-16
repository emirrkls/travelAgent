package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.model.ExperienceFeedLens;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dense, viewer-authorized keyset queries for V2 read surfaces. Authorization is
 * intentionally applied before LIMIT so clients never repair privacy-filtered pages.
 */
@Repository
public class ExperienceFeedRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ExperienceFeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FeedRow> find(FeedQuery query) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("snapshotAt", Timestamp.from(query.snapshotAt().toInstant()))
                .addValue("limit", query.limit());
        List<String> predicates = new ArrayList<>();
        predicates.add("visit.created_at <= :snapshotAt");
        addAccessPredicate(predicates, parameters, query.viewerId());

        if (query.lens() == ExperienceFeedLens.FOLLOWING) {
            if (query.viewerId() == null) return List.of();
            predicates.add("visit.user_id <> :viewerId");
            predicates.add("exists (select 1 from user_follows following "
                    + "where following.follower_user_id = :viewerId "
                    + "and following.followed_user_id = visit.user_id)");
        }
        if (query.placeId() != null) {
            predicates.add("visit.place_id = :placeId");
            parameters.addValue("placeId", query.placeId());
        }
        if (query.authorId() != null) {
            predicates.add("visit.user_id = :authorId");
            parameters.addValue("authorId", query.authorId());
        }
        if (query.primaryExperienceCode() != null) {
            if ("UNKNOWN_LEGACY".equals(query.primaryExperienceCode())) {
                predicates.add("detail.visit_id is null");
            } else {
                predicates.add("detail.primary_experience_code = :primaryExperience");
                parameters.addValue("primaryExperience", query.primaryExperienceCode());
            }
        }
        if (query.vibeCode() != null) {
            predicates.add("exists (select 1 from visit_experience_vibes vibe "
                    + "where vibe.visit_id = visit.id and vibe.vibe_code = :vibeCode)");
            parameters.addValue("vibeCode", query.vibeCode());
        }
        if (query.searchPattern() != null) {
            predicates.add("(lower(place.name) like :search escape '!' "
                    + "or lower(coalesce(detail.title, visit.public_review)) like :search escape '!' "
                    + "or lower(coalesce(detail.raw_experience_label, '')) like :search escape '!' "
                    + "or replace(lower(coalesce(detail.primary_experience_code, '')), '_', ' ') like :search escape '!' "
                    + (query.searchPrimaryCode() == null
                    ? "false)"
                    : "or detail.primary_experience_code = :searchPrimaryCode)"));
            parameters.addValue("search", query.searchPattern());
            if (query.searchPrimaryCode() != null) {
                parameters.addValue("searchPrimaryCode", query.searchPrimaryCode());
            }
        }

        String distanceExpression = "null::double precision";
        if (query.lens() == ExperienceFeedLens.NEARBY) {
            if (query.latitude() == null || query.longitude() == null || query.radiusMeters() == null) {
                return List.of();
            }
            distanceExpression = "ST_Distance(place.location::geography, "
                    + "ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)";
            predicates.add("ST_DWithin(place.location::geography, "
                    + "ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :radiusMeters)");
            parameters.addValue("latitude", query.latitude())
                    .addValue("longitude", query.longitude())
                    .addValue("radiusMeters", query.radiusMeters());
        }

        String popularityExpression = query.lens() == ExperienceFeedLens.POPULAR
                ? "(select count(*) from visits community "
                    + "where community.place_id = visit.place_id "
                    + "and community.visibility in ('PUBLIC', 'FRIENDS'))"
                : "0";
        String cte = "with eligible as (select visit.id, visit.created_at, "
                + popularityExpression + " as popularity_score, "
                + distanceExpression + " as distance_meters "
                + "from visits visit "
                + "join users author on author.id = visit.user_id "
                + "join places place on place.id = visit.place_id "
                + "left join visit_experience_details detail on detail.visit_id = visit.id "
                + "where " + String.join(" and ", predicates) + ") ";

        List<String> cursorPredicates = new ArrayList<>();
        String order;
        FeedCursor cursor = query.cursor();
        if (query.lens() == ExperienceFeedLens.POPULAR) {
            order = "popularity_score desc, created_at desc, id desc";
            if (cursor != null) {
                cursorPredicates.add("(popularity_score < :cursorScore or "
                        + "(popularity_score = :cursorScore and (created_at < :cursorCreatedAt or "
                        + "(created_at = :cursorCreatedAt and id < :cursorId))))");
                parameters.addValue("cursorScore", cursor.popularityScore());
            }
        } else if (query.lens() == ExperienceFeedLens.NEARBY) {
            order = "distance_meters asc, created_at desc, id desc";
            if (cursor != null) {
                cursorPredicates.add("(distance_meters > :cursorDistance or "
                        + "(distance_meters = :cursorDistance and (created_at < :cursorCreatedAt or "
                        + "(created_at = :cursorCreatedAt and id < :cursorId))))");
                parameters.addValue("cursorDistance", cursor.distanceMeters());
            }
        } else {
            order = "created_at desc, id desc";
            if (cursor != null) {
                cursorPredicates.add("(created_at < :cursorCreatedAt or "
                        + "(created_at = :cursorCreatedAt and id < :cursorId))");
            }
        }
        if (cursor != null) {
            parameters.addValue("cursorCreatedAt", Timestamp.from(cursor.createdAt().toInstant()))
                    .addValue("cursorId", cursor.id());
        }
        String sql = cte + "select id, created_at, popularity_score, distance_meters from eligible "
                + (cursorPredicates.isEmpty() ? "" : "where " + String.join(" and ", cursorPredicates) + " ")
                + "order by " + order + " limit :limit";
        return jdbc.query(sql, parameters, (result, ignored) -> new FeedRow(
                result.getObject("id", UUID.class),
                result.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC),
                result.getLong("popularity_score"),
                result.getObject("distance_meters") == null ? null : result.getDouble("distance_meters")));
    }

    public List<PrimaryExperienceCount> primaryDistribution(UUID placeId, UUID viewerId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("placeId", placeId);
        List<String> predicates = new ArrayList<>();
        predicates.add("visit.place_id = :placeId");
        addAccessPredicate(predicates, parameters, viewerId);
        String sql = "select coalesce(detail.primary_experience_code, 'UNKNOWN_LEGACY') as code, "
                + "count(*) as contribution_count from visits visit "
                + "join users author on author.id = visit.user_id "
                + "left join visit_experience_details detail on detail.visit_id = visit.id "
                + "where " + String.join(" and ", predicates) + " group by 1 order by 2 desc, 1 asc";
        return jdbc.query(sql, parameters, (result, ignored) -> new PrimaryExperienceCount(
                result.getString("code"), result.getLong("contribution_count")));
    }

    private static void addAccessPredicate(
            List<String> predicates, MapSqlParameterSource parameters, UUID viewerId) {
        if (viewerId == null) {
            predicates.add("visit.visibility = 'PUBLIC'");
            predicates.add("author.profile_visibility = 'PUBLIC'");
            return;
        }
        parameters.addValue("viewerId", viewerId);
        predicates.add("(visit.user_id = :viewerId or ("
                + "not exists (select 1 from user_blocks block where "
                + "(block.blocker_user_id = :viewerId and block.blocked_user_id = visit.user_id) or "
                + "(block.blocker_user_id = visit.user_id and block.blocked_user_id = :viewerId)) and ("
                + "(visit.visibility = 'PUBLIC' and (author.profile_visibility = 'PUBLIC' or "
                + "exists (select 1 from user_follows approved where "
                + "approved.follower_user_id = :viewerId and approved.followed_user_id = visit.user_id))) or "
                + "(visit.visibility = 'FRIENDS' and "
                + "exists (select 1 from user_follows outbound where outbound.follower_user_id = :viewerId "
                + "and outbound.followed_user_id = visit.user_id) and "
                + "exists (select 1 from user_follows inbound where inbound.follower_user_id = visit.user_id "
                + "and inbound.followed_user_id = :viewerId)))))");
    }

    public record FeedQuery(
            ExperienceFeedLens lens,
            UUID viewerId,
            UUID placeId,
            UUID authorId,
            String primaryExperienceCode,
            String vibeCode,
            String searchPattern,
            String searchPrimaryCode,
            Double latitude,
            Double longitude,
            Double radiusMeters,
            OffsetDateTime snapshotAt,
            FeedCursor cursor,
            int limit) {
    }

    public record FeedCursor(
            long popularityScore,
            Double distanceMeters,
            OffsetDateTime createdAt,
            UUID id) {
    }

    public record FeedRow(
            UUID id,
            OffsetDateTime createdAt,
            long popularityScore,
            Double distanceMeters) {
    }

    public record PrimaryExperienceCount(String code, long count) {
    }
}
