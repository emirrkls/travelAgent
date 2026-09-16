package com.emirrkls.phokarta.core.model

data class PlaceAggregateV2(
    val place: PlaceAggregateIdentity,
    val visibleExperienceCount: Long,
    val communityContributionCount: Long,
    val feelings: List<FeelingAggregate>,
    val dimensions: List<DimensionAggregateV2>,
    val practicalSignals: List<PracticalSignalAggregate>,
)

data class PlaceAggregateIdentity(
    val id: String,
    val name: String,
    val categoryCode: String,
    val city: String,
    val region: String,
    val country: String,
    val coverImage: String,
)

data class FeelingAggregate(val code: OverallFeelingCode, val contributionCount: Long)

data class DimensionAggregateV2(
    val key: String,
    val contributionCount: Long,
    val numericAverage: Double?,
    val legacyNumericContributionCount: Long,
    val semanticDistribution: List<SemanticStateAggregate>,
)

data class SemanticStateAggregate(
    val state: DimensionStateCode,
    val contributionCount: Long,
)

data class PracticalSignalAggregate(
    val code: PracticalSignalCode,
    val contributionCount: Long,
    val eligibleContributionDenominator: Long,
)
