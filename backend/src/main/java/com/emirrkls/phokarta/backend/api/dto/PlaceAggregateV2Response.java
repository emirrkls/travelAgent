package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.DimensionStateCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;

import java.util.List;
import java.util.UUID;

/** Aggregate-only response: no contributor or authored-content identity is represented. */
public record PlaceAggregateV2Response(
        PlaceIdentity place,
        long visibleExperienceCount,
        long communityContributionCount,
        List<FeelingCount> feelings,
        List<DimensionAggregate> dimensions,
        List<PracticalSignalAggregate> practicalSignals) {

    public record PlaceIdentity(UUID id, String name, PlaceCategory category, String city,
                                String region, String country, String coverImage) {}

    public record FeelingCount(OverallFeelingCode code, long contributionCount) {}

    public record DimensionAggregate(
            String key,
            long contributionCount,
            Double numericAverage,
            long legacyNumericContributionCount,
            List<SemanticStateCount> semanticDistribution) {}

    public record SemanticStateCount(DimensionStateCode state, long contributionCount) {}

    public record PracticalSignalAggregate(
            PracticalSignalCode code,
            long contributionCount,
            long eligibleContributionDenominator) {}
}
