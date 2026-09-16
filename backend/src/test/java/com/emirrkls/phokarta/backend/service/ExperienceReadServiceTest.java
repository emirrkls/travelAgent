package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.api.dto.VisitMediaResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScore;
import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScoreId;
import com.emirrkls.phokarta.backend.domain.entity.VisitExperienceDetail;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperienceReadServiceTest {
    @Mock private VisitRepository visits;
    @Mock private VisitExperienceDetailRepository details;
    @Mock private VisitDimensionScoreRepository dimensions;
    @Mock private MediaService media;
    @Mock private ViewerAccessPolicy access;
    @Mock private FollowRequestService relationships;
    @Mock private Visit visit;
    @Mock private User user;
    @Mock private Place place;
    @Mock private VisitDimensionScore score;
    @Mock private VisitDimensionScoreId scoreId;
    @Mock private VisitExperienceDetail detail;

    private ExperienceReadService service;
    private UUID visitId;

    @BeforeEach
    void setUp() {
        service = new ExperienceReadService(visits, details, dimensions, media, access, relationships);
        visitId = UUID.randomUUID();
        lenient().when(visits.findDetailedById(visitId)).thenReturn(Optional.of(visit));
        lenient().when(access.canViewVisit(visit, null)).thenReturn(true);
        lenient().when(visit.getId()).thenReturn(visitId);
        lenient().when(visit.getUser()).thenReturn(user);
        lenient().when(visit.getPlace()).thenReturn(place);
        lenient().when(user.getId()).thenReturn(UUID.randomUUID());
        lenient().when(user.getUsername()).thenReturn("author");
        lenient().when(user.getDisplayName()).thenReturn("Author");
        lenient().when(place.getId()).thenReturn(UUID.randomUUID());
        lenient().when(place.getName()).thenReturn("Foça");
        lenient().when(place.getCategory()).thenReturn(PlaceCategory.BEACH);
        lenient().when(place.getCity()).thenReturn("İzmir");
        lenient().when(place.getRegion()).thenReturn("Aegean");
        lenient().when(place.getCountry()).thenReturn("Türkiye");
        lenient().when(place.getCoverImage()).thenReturn("https://images.test/cover.jpg");
        lenient().when(visit.getVisitedAt()).thenReturn(LocalDate.of(2026, 9, 1));
        lenient().when(visit.getOverallRating()).thenReturn(9.0);
        lenient().when(visit.getVisibility()).thenReturn(Visibility.PUBLIC);
        lenient().when(dimensions.findByIdVisitId(visitId)).thenReturn(List.of());
        lenient().when(media.descriptorsForVisits(List.of(visitId))).thenReturn(Map.of());
    }

    @Test
    void legacyVisitMapsWithoutMutationOrPrivateMemoryLeakAndKeepsAllMedia() throws Exception {
        List<String> legacy = List.of(
                "https://legacy.test/0.jpg", "https://legacy.test/1.jpg",
                "https://legacy.test/2.jpg", "https://legacy.test/3.jpg",
                "https://legacy.test/4.jpg", "https://legacy.test/5.jpg",
                "https://legacy.test/6.jpg");
        when(details.findById(visitId)).thenReturn(Optional.empty());
        when(visit.getPublicReview()).thenReturn("Public story");
        when(visit.getPhotos()).thenReturn(legacy);

        ExperienceV2Response response = service.getVisible(visitId, null);

        assertThat(response.id()).isEqualTo(visitId);
        assertThat(response.classification()).isEqualTo(ExperienceV2Response.Classification.LEGACY_COMPATIBILITY);
        assertThat(response.story()).isEqualTo("Public story");
        assertThat(response.tip()).isNull();
        assertThat(response.title()).isEqualTo("Foça");
        assertThat(response.titleSource()).isNull();
        assertThat(response.titlePersisted()).isFalse();
        assertThat(response.feeling().code()).isEqualTo(ExperienceTaxonomy.OverallFeelingCode.BAYILDIM);
        assertThat(response.feeling().source()).isEqualTo(ExperienceTaxonomy.FeelingSource.DERIVED_LEGACY);
        assertThat(response.feeling().compatibilityNumericRating()).isEqualTo(9.0);
        assertThat(response.primaryExperience().code()).isEqualTo("UNKNOWN_LEGACY");
        assertThat(response.primaryExperience().canonical()).isFalse();
        assertThat(response.media()).extracting(ExperienceV2Response.Media::url).containsExactlyElementsOf(legacy);
        assertThat(response.visibility()).isEqualTo(Visibility.PUBLIC);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertThat(json).doesNotContain("privateMemory", "must-never-leak");
    }

    @Test
    void nativeDetailRoundTripsPersistedTitleContextAndSemanticDimension() {
        UUID mediaId = UUID.randomUUID();
        when(details.findById(visitId)).thenReturn(Optional.of(detail));
        when(detail.getPrimaryExperienceCode()).thenReturn(ExperienceTaxonomy.PrimaryExperienceCode.GUN_BATIMI);
        when(detail.getOverallFeelingCode()).thenReturn(ExperienceTaxonomy.OverallFeelingCode.GUZELDI);
        when(detail.getFeelingSource()).thenReturn(ExperienceTaxonomy.FeelingSource.EXPLICIT);
        when(detail.getCompanionCode()).thenReturn(ExperienceTaxonomy.CompanionCode.PARTNER);
        when(detail.getTimeOfDayCode()).thenReturn(ExperienceTaxonomy.TimeOfDayCode.EVENING);
        when(detail.getTitle()).thenReturn("Foça’da sakin bir gün batımı");
        when(detail.getTitleSource()).thenReturn(ExperienceTaxonomy.TitleSource.GENERATED);
        when(detail.getStory()).thenReturn("Native story");
        when(detail.getTip()).thenReturn("Arrive early");
        when(detail.getTaxonomyVersion()).thenReturn(1);
        when(detail.getVibes()).thenReturn(List.of(
                ExperienceTaxonomy.VibeCode.CALM, ExperienceTaxonomy.VibeCode.ROMANTIC));
        when(detail.getPracticalSignals()).thenReturn(List.of(
                ExperienceTaxonomy.PracticalSignalCode.ARRIVE_EARLY));
        when(score.getId()).thenReturn(scoreId);
        when(scoreId.getDimensionKey()).thenReturn("SCENERY");
        when(score.getScore()).thenReturn(10.0);
        when(score.getSemanticStateCode()).thenReturn(ExperienceTaxonomy.DimensionStateCode.VERY_GOOD);
        when(score.getTemplateVersion()).thenReturn(1);
        when(dimensions.findByIdVisitId(visitId)).thenReturn(List.of(score));
        when(visit.getPhotos()).thenReturn(List.of());
        when(media.descriptorsForVisits(List.of(visitId))).thenReturn(Map.of(
                visitId, List.of(new VisitMediaResponse(mediaId, 0,
                        URI.create("https://media.test/signed"), OffsetDateTime.parse("2026-09-16T12:00:00Z")))));

        ExperienceV2Response response = service.getVisible(visitId, null);

        assertThat(response.classification()).isEqualTo(ExperienceV2Response.Classification.NATIVE_V2);
        assertThat(response.title()).isEqualTo("Foça’da sakin bir gün batımı");
        assertThat(response.titleSource()).isEqualTo(ExperienceTaxonomy.TitleSource.GENERATED);
        assertThat(response.titlePersisted()).isTrue();
        assertThat(response.feeling().source()).isEqualTo(ExperienceTaxonomy.FeelingSource.EXPLICIT);
        assertThat(response.primaryExperience().family())
                .isEqualTo(ExperienceTaxonomy.ExperienceFamily.SCENERY_AND_MOMENT);
        assertThat(response.vibes()).containsExactly(
                ExperienceTaxonomy.VibeCode.CALM, ExperienceTaxonomy.VibeCode.ROMANTIC);
        assertThat(response.practicalSignals())
                .containsExactly(ExperienceTaxonomy.PracticalSignalCode.ARRIVE_EARLY);
        assertThat(response.dimensions()).singleElement().satisfies(value -> {
            assertThat(value.numericScore()).isEqualTo(10.0);
            assertThat(value.semanticState()).isEqualTo(ExperienceTaxonomy.DimensionStateCode.VERY_GOOD);
            assertThat(value.templateVersion()).isEqualTo(1);
        });
        assertThat(response.media()).singleElement().satisfies(value -> {
            assertThat(value.kind()).isEqualTo(ExperienceV2Response.MediaKind.MANAGED);
            assertThat(value.id()).isEqualTo(mediaId);
        });

        when(detail.getTitle()).thenReturn("My exact custom title");
        when(detail.getTitleSource()).thenReturn(ExperienceTaxonomy.TitleSource.CUSTOM);
        ExperienceV2Response custom = service.getVisible(visitId, null);
        assertThat(custom.title()).isEqualTo("My exact custom title");
        assertThat(custom.titleSource()).isEqualTo(ExperienceTaxonomy.TitleSource.CUSTOM);
        assertThat(custom.titlePersisted()).isTrue();
    }

    @Test
    void blockedViewerGetsNotFoundThroughCentralPolicy() {
        UUID viewer = UUID.randomUUID();
        when(access.canViewVisit(visit, viewer)).thenReturn(false);

        assertThatThrownBy(() -> service.getVisible(visitId, viewer))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
        verify(details, never()).findById(visitId);
    }
}
