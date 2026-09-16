package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.PlaceAggregateV2Response;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceAggregateV2ServiceTest {
    @Mock private PlaceRepository places;
    @Mock private VisitRepository visits;
    @Mock private VisitExperienceDetailRepository details;
    @Mock private VisitDimensionScoreRepository dimensions;
    @Mock private Place place;
    private PlaceAggregateV2Service service;
    private UUID placeId;

    @BeforeEach
    void setUp() {
        service = new PlaceAggregateV2Service(places, visits, details, dimensions);
        placeId = UUID.randomUUID();
        when(places.findById(placeId)).thenReturn(Optional.of(place));
        when(place.getId()).thenReturn(placeId);
        when(place.getName()).thenReturn("Foça");
        when(place.getCategory()).thenReturn(PlaceCategory.BEACH);
        when(details.aggregateFeelings(placeId)).thenReturn(List.of());
        when(details.aggregatePracticalSignals(placeId)).thenReturn(List.of());
        when(dimensions.aggregateV2ForPlace(placeId)).thenReturn(List.of());
        when(dimensions.aggregateSemanticStatesForPlace(placeId)).thenReturn(List.of());
    }

    @Test
    void feelingDistributionAlwaysContainsAllStableCodes() {
        var response = service.get(placeId, null);
        assertThat(response.feelings()).hasSize(5);
        assertThat(response.feelings()).allMatch(value -> value.contributionCount() == 0);
    }

    @Test
    void visibleCountAndGlobalContributionCountAreIndependent() {
        UUID viewer = UUID.randomUUID();
        when(visits.countVisibleAtPlace(placeId, viewer)).thenReturn(1L);
        when(visits.countCommunityContributions(placeId)).thenReturn(4L);
        var response = service.get(placeId, viewer);
        assertThat(response.visibleExperienceCount()).isEqualTo(1L);
        assertThat(response.communityContributionCount()).isEqualTo(4L);
    }

    @Test
    void legacyNumericDimensionIsRepresentedWithoutFabricatedSemanticState() {
        VisitDimensionScoreRepository.V2DimensionAggregateRow row =
                org.mockito.Mockito.mock(VisitDimensionScoreRepository.V2DimensionAggregateRow.class);
        when(row.getDimensionKey()).thenReturn("SEA");
        when(row.getContributionCount()).thenReturn(2L);
        when(row.getNumericAverage()).thenReturn(7.5);
        when(row.getLegacyNumericContributionCount()).thenReturn(2L);
        when(dimensions.aggregateV2ForPlace(placeId)).thenReturn(List.of(row));
        PlaceAggregateV2Response.DimensionAggregate value =
                service.get(placeId, null).dimensions().getFirst();
        assertThat(value.numericAverage()).isEqualTo(7.5);
        assertThat(value.legacyNumericContributionCount()).isEqualTo(2L);
        assertThat(value.semanticDistribution()).isEmpty();
    }

    @Test
    void practicalSignalUsesEligiblePopulationDenominator() {
        VisitExperienceDetailRepository.PracticalSignalAggregateRow row =
                org.mockito.Mockito.mock(VisitExperienceDetailRepository.PracticalSignalAggregateRow.class);
        when(row.getSignalCode()).thenReturn("ARRIVE_EARLY");
        when(row.getContributionCount()).thenReturn(2L);
        when(details.aggregatePracticalSignals(placeId)).thenReturn(List.of(row));
        when(visits.countCommunityContributions(placeId)).thenReturn(5L);
        var signal = service.get(placeId, null).practicalSignals().getFirst();
        assertThat(signal.contributionCount()).isEqualTo(2L);
        assertThat(signal.eligibleContributionDenominator()).isEqualTo(5L);
    }

    @Test
    void aggregateJsonHasNoContributorIdentitySurface() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(service.get(placeId, null));
        assertThat(json).doesNotContain("author", "username", "userId", "privateMemory");
    }
}
