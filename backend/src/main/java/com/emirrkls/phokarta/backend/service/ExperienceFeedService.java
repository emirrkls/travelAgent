package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CursorPageResponse;
import com.emirrkls.phokarta.backend.api.dto.ExperienceSummaryV2Response;
import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.api.dto.RelationshipV2Response;
import com.emirrkls.phokarta.backend.api.dto.VisitMediaResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitExperienceDetail;
import com.emirrkls.phokarta.backend.domain.model.ExperienceFeedLens;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.FeelingSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode;
import com.emirrkls.phokarta.backend.repository.ExperienceFeedRepository;
import com.emirrkls.phokarta.backend.repository.ExperienceFeedRepository.FeedCursor;
import com.emirrkls.phokarta.backend.repository.ExperienceFeedRepository.FeedQuery;
import com.emirrkls.phokarta.backend.repository.ExperienceFeedRepository.FeedRow;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.repository.PlannedExperienceRepository;
import com.emirrkls.phokarta.backend.repository.ExperienceAcknowledgementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ExperienceFeedService {
    private static final int MAX_PREVIEW_STORY = 280;
    private static final int MAX_PREVIEW_TIP = 160;
    private static final Map<String, String> SEARCH_PRIMARY_ALIASES = Map.ofEntries(
            Map.entry("sunset", "GUN_BATIMI"),
            Map.entry("gunbatimi", "GUN_BATIMI"),
            Map.entry("gun batimi", "GUN_BATIMI"),
            Map.entry("gunes batisi", "GUN_BATIMI"),
            Map.entry("breakfast", "KAHVALTI"),
            Map.entry("kahvalti", "KAHVALTI"),
            Map.entry("coffee", "KAHVE"),
            Map.entry("hiking", "DOGA_YURUYUSU"),
            Map.entry("trekking", "DOGA_YURUYUSU"),
            Map.entry("doga yuruyusu", "DOGA_YURUYUSU"),
            Map.entry("beach", "PLAJ"),
            Map.entry("plaj", "PLAJ"));

    private final ExperienceFeedRepository feed;
    private final VisitRepository visits;
    private final VisitExperienceDetailRepository details;
    private final MediaService media;
    private final FollowRequestService relationships;
    private final PlannedExperienceRepository plans;
    private final ExperienceAcknowledgementRepository acknowledgements;
    private final ExperienceConversationService conversations;

    public ExperienceFeedService(
            ExperienceFeedRepository feed,
            VisitRepository visits,
            VisitExperienceDetailRepository details,
            MediaService media,
            FollowRequestService relationships,
            PlannedExperienceRepository plans,
            ExperienceAcknowledgementRepository acknowledgements,
            ExperienceConversationService conversations) {
        this.feed = feed;
        this.visits = visits;
        this.details = details;
        this.media = media;
        this.relationships = relationships;
        this.plans = plans;
        this.acknowledgements = acknowledgements;
        this.conversations = conversations;
    }

    public CursorPageResponse<ExperienceSummaryV2Response> explore(
            ExperienceFeedLens lens,
            UUID viewerId,
            String cursor,
            int size,
            String search,
            String primary,
            String vibe,
            Double latitude,
            Double longitude,
            Double radiusMeters) {
        return load(new Request(lens, viewerId, null, null, cursor, size, search,
                primary, vibe, latitude, longitude, radiusMeters));
    }

    public CursorPageResponse<ExperienceSummaryV2Response> forPlace(
            UUID placeId, UUID viewerId, String cursor, int size, String primary) {
        return load(new Request(ExperienceFeedLens.FOR_YOU, viewerId, placeId, null,
                cursor, size, null, primary, null, null, null, null));
    }

    public CursorPageResponse<ExperienceSummaryV2Response> forProfile(
            UUID authorId, UUID viewerId, String cursor, int size) {
        return load(new Request(ExperienceFeedLens.FOR_YOU, viewerId, null, authorId,
                cursor, size, null, null, null, null, null, null));
    }

    public List<ExperienceFeedRepository.PrimaryExperienceCount> primaryDistribution(
            UUID placeId, UUID viewerId) {
        return feed.primaryDistribution(placeId, viewerId);
    }

    private CursorPageResponse<ExperienceSummaryV2Response> load(Request request) {
        validate(request);
        String normalizedSearch = normalized(request.search());
        String normalizedPrimary = normalizedCode(request.primary());
        String normalizedVibe = normalizedCode(request.vibe());
        String scope = scope(request, normalizedSearch, normalizedPrimary, normalizedVibe);
        DecodedCursor decoded = decode(request.cursor());
        if (decoded != null && !decoded.scope().equals(scope)) {
            throw ApiException.validation("Cursor does not belong to this Experience feed");
        }
        OffsetDateTime snapshot = decoded == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                : decoded.snapshotAt();
        FeedCursor repositoryCursor = decoded == null ? null : new FeedCursor(
                decoded.popularityScore(), decoded.distanceMeters(), decoded.createdAt(), decoded.id());
        String searchPrimary = normalizedSearch == null ? null
                : SEARCH_PRIMARY_ALIASES.get(fold(normalizedSearch));
        String searchPattern = normalizedSearch == null ? null
                : "%" + escapeLike(normalizedSearch.toLowerCase(Locale.ROOT)) + "%";

        List<FeedRow> rows = feed.find(new FeedQuery(
                request.lens(), request.viewerId(), request.placeId(), request.authorId(),
                normalizedPrimary, normalizedVibe, searchPattern, searchPrimary,
                request.latitude(), request.longitude(), request.radiusMeters(),
                snapshot, repositoryCursor, request.size() + 1));
        boolean hasMore = rows.size() > request.size();
        List<FeedRow> pageRows = hasMore ? rows.subList(0, request.size()) : rows;
        List<ExperienceSummaryV2Response> summaries = map(pageRows, request.viewerId());
        if (request.lens() == ExperienceFeedLens.FOR_YOU
                && request.placeId() == null && request.authorId() == null) {
            summaries = diversify(summaries);
        }
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? encode(scope, snapshot, pageRows.get(pageRows.size() - 1))
                : null;
        return new CursorPageResponse<>(summaries, nextCursor, hasMore);
    }

    private List<ExperienceSummaryV2Response> map(List<FeedRow> rows, UUID viewerId) {
        if (rows.isEmpty()) return List.of();
        List<UUID> ids = rows.stream().map(FeedRow::id).toList();
        Map<UUID, Visit> visitById = new HashMap<>();
        visits.findDetailedByIds(ids).forEach(value -> visitById.put(value.getId(), value));
        Map<UUID, VisitExperienceDetail> detailById = new HashMap<>();
        details.findAllById(ids).forEach(value -> detailById.put(value.getVisitId(), value));

        Map<UUID, List<VibeCode>> vibesById = new HashMap<>();
        details.findVibesByVisitIds(ids).forEach(value -> vibesById
                .computeIfAbsent(value.getVisitId(), ignored -> new ArrayList<>())
                .add(VibeCode.valueOf(value.getCode())));
        Map<UUID, List<PracticalSignalCode>> practicalById = new HashMap<>();
        details.findPracticalSignalsByVisitIds(ids).forEach(value -> practicalById
                .computeIfAbsent(value.getVisitId(), ignored -> new ArrayList<>())
                .add(PracticalSignalCode.valueOf(value.getCode())));
        Map<UUID, List<VisitMediaResponse>> managedById = media.descriptorsForVisits(ids);
        Map<UUID, Double> distanceById = new HashMap<>();
        rows.forEach(value -> distanceById.put(value.id(), value.distanceMeters()));

        List<UUID> authorIds = visitById.values().stream()
                .map(value -> value.getUser().getId()).distinct().toList();
        Map<UUID, RelationshipV2Response> relationshipByAuthor =
                relationships.relationships(viewerId, authorIds);

        List<ExperienceSummaryV2Response> result = new ArrayList<>(rows.size());
        for (FeedRow row : rows) {
            Visit visit = visitById.get(row.id());
            if (visit == null) continue;
            VisitExperienceDetail detail = detailById.get(row.id());
            boolean nativeV2 = detail != null;
            var primary = nativeV2
                    ? new ExperienceV2Response.PrimaryExperience(
                            detail.getPrimaryExperienceCode().name(), true,
                            ExperienceTaxonomy.familyOf(detail.getPrimaryExperienceCode()),
                            detail.getRawExperienceLabel())
                    : new ExperienceV2Response.PrimaryExperience(
                            ExperienceReadService.UNKNOWN_LEGACY, false, null, null);
            List<VisitMediaResponse> managed = managedById.getOrDefault(row.id(), List.of());
            ExperienceSummaryV2Response.MediaPreview preview = mediaPreview(visit, managed);
            int mediaCount = visit.getPhotos().size() + managed.size();
            String story = nativeV2 ? detail.getStory() : visit.getPublicReview();
            String tip = nativeV2 ? detail.getTip() : null;
            UUID authorId = visit.getUser().getId();
            result.add(new ExperienceSummaryV2Response(
                    visit.getId(), nativeV2
                            ? ExperienceV2Response.Classification.NATIVE_V2
                            : ExperienceV2Response.Classification.LEGACY_COMPATIBILITY,
                    new ExperienceSummaryV2Response.Author(
                            authorId, visit.getUser().getUsername(), visit.getUser().getDisplayName(),
                            visit.getUser().getAvatarUrl(), relationshipByAuthor.get(authorId)),
                    new ExperienceSummaryV2Response.Place(
                            visit.getPlace().getId(), visit.getPlace().getName(),
                            visit.getPlace().getCategory(), visit.getPlace().getCity(),
                            visit.getPlace().getRegion(), visit.getPlace().getCountry(),
                            visit.getPlace().getCoverImage(), distanceById.get(row.id())),
                    visit.getVisitedAt(), nativeV2 ? detail.getTitle() : visit.getPlace().getName(),
                    nativeV2 ? detail.getTitleSource() : null,
                    primary,
                    nativeV2 ? detail.getOverallFeelingCode()
                            : ExperienceTaxonomy.OverallFeelingCode.fromLegacyRating(visit.getOverallRating()),
                    preview(story, MAX_PREVIEW_STORY), preview(tip, MAX_PREVIEW_TIP),
                    nativeV2 ? detail.getCompanionCode() : null,
                    nativeV2 ? detail.getTimeOfDayCode() : null,
                    List.copyOf(vibesById.getOrDefault(row.id(), List.of())),
                    List.copyOf(practicalById.getOrDefault(row.id(), List.of()).stream().limit(2).toList()),
                    preview, mediaCount, visit.getVisibility(),
                    viewerId != null && plans.existsByIdUserIdAndIdExperienceId(viewerId, visit.getId()),
                    viewerId != null && acknowledgements.existsByUserIdAndSourceExperienceId(viewerId, visit.getId()),
                    acknowledgements.countBySourceExperienceId(visit.getId()),
                    conversations.visibleRootCount(visit.getId(), viewerId)));
        }
        return List.copyOf(result);
    }

    private static ExperienceSummaryV2Response.MediaPreview mediaPreview(
            Visit visit, List<VisitMediaResponse> managed) {
        if (!visit.getPhotos().isEmpty()) {
            return new ExperienceSummaryV2Response.MediaPreview(
                    ExperienceV2Response.MediaKind.LEGACY_URL, null,
                    visit.getPhotos().get(0), null);
        }
        return managed.stream().min(Comparator.comparingInt(VisitMediaResponse::sortOrder))
                .map(value -> new ExperienceSummaryV2Response.MediaPreview(
                        ExperienceV2Response.MediaKind.MANAGED, value.id(),
                        value.accessUrl().toString(), value.accessExpiresAt()))
                .orElse(null);
    }

    /** Greedy page-local diversity while keeping every database-selected item. */
    static List<ExperienceSummaryV2Response> diversify(List<ExperienceSummaryV2Response> input) {
        if (input.size() < 3) return input;
        List<ExperienceSummaryV2Response> remaining = new ArrayList<>(input);
        List<ExperienceSummaryV2Response> result = new ArrayList<>(input.size());
        while (!remaining.isEmpty()) {
            ExperienceSummaryV2Response previous = result.isEmpty() ? null : result.get(result.size() - 1);
            int selected = 0;
            if (previous != null) {
                for (int index = 0; index < remaining.size(); index++) {
                    ExperienceSummaryV2Response candidate = remaining.get(index);
                    if (!candidate.author().id().equals(previous.author().id())
                            && !candidate.place().id().equals(previous.place().id())
                            && !candidate.primaryExperience().code()
                                    .equals(previous.primaryExperience().code())) {
                        selected = index;
                        break;
                    }
                }
            }
            result.add(remaining.remove(selected));
        }
        return List.copyOf(result);
    }

    private static void validate(Request request) {
        if (request.size() < 1 || request.size() > 50) {
            throw ApiException.validation("size must be between 1 and 50");
        }
        if (request.lens() == ExperienceFeedLens.FOLLOWING && request.viewerId() == null) {
            throw ApiException.unauthorized("AUTHENTICATION_REQUIRED",
                    "Following requires authentication");
        }
        if (request.lens() == ExperienceFeedLens.NEARBY) {
            if (request.latitude() == null || request.longitude() == null) {
                throw ApiException.validation("Nearby requires latitude and longitude");
            }
            if (request.latitude() < -90 || request.latitude() > 90
                    || request.longitude() < -180 || request.longitude() > 180) {
                throw ApiException.validation("Invalid Nearby coordinates");
            }
            if (request.radiusMeters() == null || request.radiusMeters() < 100
                    || request.radiusMeters() > 100_000) {
                throw ApiException.validation("radiusMeters must be between 100 and 100000");
            }
        }
        String primary = normalizedCode(request.primary());
        if (primary != null && !"UNKNOWN_LEGACY".equals(primary)) {
            try {
                PrimaryExperienceCode.valueOf(primary);
            } catch (IllegalArgumentException invalid) {
                throw ApiException.validation("Unknown Primary Experience");
            }
        }
        String vibe = normalizedCode(request.vibe());
        if (vibe != null) {
            try {
                VibeCode.valueOf(vibe);
            } catch (IllegalArgumentException invalid) {
                throw ApiException.validation("Unknown Vibe");
            }
        }
    }

    private static String scope(
            Request request, String search, String primary, String vibe) {
        String raw = String.join("|",
                request.lens().name(), value(request.viewerId()), value(request.placeId()),
                value(request.authorId()), value(search), value(primary), value(vibe),
                value(request.latitude()), value(request.longitude()), value(request.radiusMeters()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(String scope, OffsetDateTime snapshot, FeedRow row) {
        String raw = String.join("|", "1", scope, snapshot.toString(),
                Long.toString(row.popularityScore()),
                row.distanceMeters() == null ? "" : Double.toString(row.distanceMeters()),
                row.createdAt().toString(), row.id().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static DecodedCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 7 || !"1".equals(fields[0])) throw new IllegalArgumentException();
            return new DecodedCursor(fields[1], OffsetDateTime.parse(fields[2]),
                    Long.parseLong(fields[3]), fields[4].isBlank() ? null : Double.parseDouble(fields[4]),
                    OffsetDateTime.parse(fields[5]), UUID.fromString(fields[6]));
        } catch (RuntimeException invalid) {
            throw ApiException.validation("Invalid Experience feed cursor");
        }
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    private static String normalizedCode(String value) {
        String result = normalized(value);
        return result == null ? null : result.toUpperCase(Locale.ROOT);
    }

    private static String preview(String value, int maxLength) {
        String normalized = normalized(value);
        if (normalized == null) return null;
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength - 1).stripTrailing() + "…";
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String fold(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
                .replace('ş', 's').replace('ö', 'o').replace('ç', 'c');
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private record Request(
            ExperienceFeedLens lens,
            UUID viewerId,
            UUID placeId,
            UUID authorId,
            String cursor,
            int size,
            String search,
            String primary,
            String vibe,
            Double latitude,
            Double longitude,
            Double radiusMeters) {
    }

    private record DecodedCursor(
            String scope,
            OffsetDateTime snapshotAt,
            long popularityScore,
            Double distanceMeters,
            OffsetDateTime createdAt,
            UUID id) {
    }
}
