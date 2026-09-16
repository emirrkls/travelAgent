package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.PlaceAggregateV2Response;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.DimensionStateCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PlaceAggregateV2Service {
    private final PlaceRepository places;
    private final VisitRepository visits;
    private final VisitExperienceDetailRepository details;
    private final VisitDimensionScoreRepository dimensions;
    private final ExperienceFeedService experienceFeeds;

    public PlaceAggregateV2Service(PlaceRepository places, VisitRepository visits,
                                   VisitExperienceDetailRepository details,
                                   VisitDimensionScoreRepository dimensions,
                                   ExperienceFeedService experienceFeeds) {
        this.places = places;
        this.visits = visits;
        this.details = details;
        this.dimensions = dimensions;
        this.experienceFeeds = experienceFeeds;
    }

    public PlaceAggregateV2Response get(UUID placeId, UUID viewerId) {
        Place place = places.findById(placeId)
                .orElseThrow(() -> ApiException.notFound("Place", placeId));
        long contributionCount = visits.countCommunityContributions(placeId);
        long visibleCount = viewerId == null
                ? visits.countVisibleAtPlaceAnonymous(placeId)
                : visits.countVisibleAtPlace(placeId, viewerId);

        Map<OverallFeelingCode, Long> feelingCounts = new EnumMap<>(OverallFeelingCode.class);
        for (OverallFeelingCode code : OverallFeelingCode.values()) feelingCounts.put(code, 0L);
        details.aggregateFeelings(placeId).forEach(row -> feelingCounts.put(
                OverallFeelingCode.valueOf(row.getFeelingCode()), row.getContributionCount()));
        List<PlaceAggregateV2Response.FeelingCount> feelings = feelingCounts.entrySet().stream()
                .map(entry -> new PlaceAggregateV2Response.FeelingCount(entry.getKey(), entry.getValue()))
                .toList();

        Map<String, List<PlaceAggregateV2Response.SemanticStateCount>> semantic = new LinkedHashMap<>();
        dimensions.aggregateSemanticStatesForPlace(placeId).forEach(row -> semantic
                .computeIfAbsent(row.getDimensionKey(), ignored -> new ArrayList<>())
                .add(new PlaceAggregateV2Response.SemanticStateCount(
                        DimensionStateCode.valueOf(row.getSemanticStateCode()),
                        row.getContributionCount())));
        List<PlaceAggregateV2Response.DimensionAggregate> dimensionAggregates =
                dimensions.aggregateV2ForPlace(placeId).stream()
                        .map(row -> new PlaceAggregateV2Response.DimensionAggregate(
                                row.getDimensionKey(), row.getContributionCount(),
                                row.getNumericAverage(), row.getLegacyNumericContributionCount(),
                                List.copyOf(semantic.getOrDefault(row.getDimensionKey(), List.of()))))
                        .toList();

        List<PlaceAggregateV2Response.PracticalSignalAggregate> signals =
                details.aggregatePracticalSignals(placeId).stream()
                        .map(row -> new PlaceAggregateV2Response.PracticalSignalAggregate(
                                PracticalSignalCode.valueOf(row.getSignalCode()),
                                row.getContributionCount(), contributionCount))
                        .toList();

        List<PlaceAggregateV2Response.PrimaryExperienceCount> primaryExperiences =
                experienceFeeds.primaryDistribution(placeId, viewerId).stream()
                        .map(value -> new PlaceAggregateV2Response.PrimaryExperienceCount(
                                value.code(), value.count()))
                        .toList();

        return new PlaceAggregateV2Response(
                new PlaceAggregateV2Response.PlaceIdentity(place.getId(), place.getName(),
                        place.getCategory(), place.getCity(), place.getRegion(), place.getCountry(),
                        place.getCoverImage()),
                visibleCount, contributionCount, primaryExperiences,
                feelings, dimensionAggregates, signals);
    }
}
