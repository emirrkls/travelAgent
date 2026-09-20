package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CreateExperienceV2Request;
import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitExperienceDetail;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.CompanionCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.DimensionStateCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.FeelingSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TimeOfDayCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TitleSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.repository.ExperienceAcknowledgementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperienceWriteServiceTest {
    @Mock VisitRepository visits;
    @Mock VisitExperienceDetailRepository details;
    @Mock VisitDimensionScoreRepository dimensions;
    @Mock UserRepository users;
    @Mock PlaceRepository places;
    @Mock MediaService media;
    @Mock UgcPolicyService ugcPolicy;
    @Mock ExperienceReadService reader;
    @Mock ExperienceAcknowledgementRepository acknowledgements;
    @Mock User user;
    @Mock Place place;
    @Mock ExperienceV2Response response;
    private ExperienceWriteService service;
    private UUID userId;
    private UUID placeId;

    @BeforeEach
    void setUp() {
        service = new ExperienceWriteService(
                visits, details, dimensions, users, places, media, ugcPolicy, reader, acknowledgements);
        userId = UUID.randomUUID();
        placeId = UUID.randomUUID();
        lenient().when(users.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(places.findById(placeId)).thenReturn(Optional.of(place));
        lenient().when(place.getName()).thenReturn("Foça");
        lenient().when(visits.findByUserIdAndClientMutationId(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void nativeFeelingCompatibilityScoresAreLocked() {
        assertThat(OverallFeelingCode.BAYILDIM.compatibilityScore()).isEqualTo(10.0);
        assertThat(OverallFeelingCode.GUZELDI.compatibilityScore()).isEqualTo(8.0);
        assertThat(OverallFeelingCode.EH_ISTE.compatibilityScore()).isEqualTo(6.0);
        assertThat(OverallFeelingCode.BEKLENTIMI_KARSILAMADI.compatibilityScore()).isEqualTo(4.0);
        assertThat(OverallFeelingCode.BIR_DAHA_TERCIH_ETMEM.compatibilityScore()).isEqualTo(2.0);
    }

    @Test
    void storyTipAndMediaEachSatisfyMinimumContent() {
        assertThat(service.canonicalize(builder().story("story").build(), place).story()).isEqualTo("story");
        assertThat(service.canonicalize(builder().story(null).tip("tip").build(), place).tip()).isEqualTo("tip");
        assertThat(service.canonicalize(builder().story(null).media(List.of(UUID.randomUUID())).build(), place)
                .mediaIds()).hasSize(1);
    }

    @Test
    void metadataOnlyAndMoreThanSixMediaAreRejected() {
        assertValidation(builder().story(" ").build(), "requires media");
        assertValidation(builder().media(List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).build(), "at most 6");
    }

    @Test
    void otherLabelAndGeneratedCustomTitleRulesAreStrict() {
        assertValidation(builder().primary(PrimaryExperienceCode.OTHER).raw(null).build(), "required for OTHER");
        assertValidation(builder().raw("not allowed").build(), "only allowed for OTHER");
        assertValidation(builder().titleSource(TitleSource.CUSTOM).title(" ").build(), "title is required");
        assertValidation(builder().titleSource(TitleSource.GENERATED).title("client generated").build(), "must be omitted");

        var generated = service.canonicalize(builder().build(), place);
        var custom = service.canonicalize(builder().titleSource(TitleSource.CUSTOM).title("My title").build(), place);
        assertThat(generated.title()).isEqualTo("Foça · gun batimi");
        assertThat(custom.title()).isEqualTo("My title");
    }

    @Test
    void contextSetsAndDimensionsAreCanonicalAndStrict() {
        assertValidation(builder().vibes(List.of(VibeCode.CALM, VibeCode.CALM)).build(), "duplicates");
        assertValidation(builder().signals(List.of(PracticalSignalCode.FREE, PracticalSignalCode.FREE)).build(), "duplicates");
        assertValidation(builder().dimensions(List.of(dimension("NOT_ALLOWED", DimensionStateCode.GOOD))).build(), "not valid");
        assertValidation(builder().dimensions(List.of(new CreateExperienceV2Request.Dimension(
                "SCENERY", DimensionStateCode.GOOD, 2))).build(), "templateVersion");

        var canonical = service.canonicalize(builder()
                .vibes(List.of(VibeCode.ROMANTIC, VibeCode.CALM))
                .signals(List.of(PracticalSignalCode.FREE, PracticalSignalCode.ARRIVE_EARLY))
                .dimensions(List.of(dimension("tranquility", DimensionStateCode.VERY_GOOD),
                        dimension("scenery", DimensionStateCode.MEDIUM)))
                .build(), place);
        assertThat(canonical.vibes()).containsExactly(VibeCode.CALM, VibeCode.ROMANTIC);
        assertThat(canonical.practicalSignals()).containsExactly(
                PracticalSignalCode.ARRIVE_EARLY, PracticalSignalCode.FREE);
        assertThat(canonical.dimensions()).extracting(CreateExperienceV2Request.Dimension::key)
                .containsExactly("SCENERY", "TRANQUILITY");
    }

    @Test
    void everySemanticFieldAffectsFingerprintWhileSetsCanonicalize() {
        Builder base = builder().titleSource(TitleSource.CUSTOM).title("Card");
        String fingerprint = service.canonicalize(base.build(), place).fingerprint();
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card")
                .date(LocalDate.of(2026, 9, 15)));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").feeling(OverallFeelingCode.GUZELDI));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").primary(PrimaryExperienceCode.MANZARA));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").companion(CompanionCode.FRIENDS));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").time(TimeOfDayCode.NIGHT));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").vibes(List.of(VibeCode.CALM)));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").signals(List.of(PracticalSignalCode.FREE)));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card")
                .dimensions(List.of(dimension("SCENERY", DimensionStateCode.GOOD))));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").story("changed"));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").tip("tip"));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").memory("private"));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Changed"));
        assertChanged(fingerprint, builder().titleSource(TitleSource.GENERATED).title(null));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card").visibility(Visibility.FRIENDS));
        assertChanged(fingerprint, builder().titleSource(TitleSource.CUSTOM).title("Card")
                .media(List.of(UUID.randomUUID())));

        String goodDimension = service.canonicalize(base
                .dimensions(List.of(dimension("SCENERY", DimensionStateCode.GOOD))).build(), place).fingerprint();
        String weakDimension = service.canonicalize(base
                .dimensions(List.of(dimension("SCENERY", DimensionStateCode.WEAK))).build(), place).fingerprint();
        assertThat(weakDimension).isNotEqualTo(goodDimension);

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String ordered = service.canonicalize(base.media(List.of(first, second)).build(), place).fingerprint();
        String reversed = service.canonicalize(base.media(List.of(second, first)).build(), place).fingerprint();
        assertThat(reversed).isNotEqualTo(ordered);

        String setsOne = service.canonicalize(base.vibes(List.of(VibeCode.ROMANTIC, VibeCode.CALM)).build(), place).fingerprint();
        String setsTwo = service.canonicalize(base.vibes(List.of(VibeCode.CALM, VibeCode.ROMANTIC)).build(), place).fingerprint();
        assertThat(setsTwo).isEqualTo(setsOne);

        String otherOne = service.canonicalize(builder()
                .primary(PrimaryExperienceCode.OTHER).raw("waterfront ritual").build(), place).fingerprint();
        String otherTwo = service.canonicalize(builder()
                .primary(PrimaryExperienceCode.OTHER).raw("harbor tradition").build(), place).fingerprint();
        assertThat(otherTwo).isNotEqualTo(otherOne);
    }

    @Test
    void createPersistsOneVisitSidecarSemanticDimensionsAndMediaAtomically() {
        when(visits.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(details.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(media.attach(any(), any(), anyList())).thenReturn(List.of());
        when(reader.map(any(), any(), anyList(), anyList())).thenReturn(response);

        var request = builder()
                .dimensions(List.of(dimension("SCENERY", DimensionStateCode.VERY_GOOD)))
                .build();
        assertThat(service.create(userId, request)).isSameAs(response);

        ArgumentCaptor<Visit> visit = ArgumentCaptor.forClass(Visit.class);
        ArgumentCaptor<VisitExperienceDetail> detail = ArgumentCaptor.forClass(VisitExperienceDetail.class);
        verify(visits).save(visit.capture());
        verify(details).save(detail.capture());
        assertThat(visit.getValue().getOverallRating()).isEqualTo(10.0);
        assertThat(visit.getValue().getPublicReview()).isEqualTo("story");
        assertThat(detail.getValue().getOverallFeelingCode()).isEqualTo(OverallFeelingCode.BAYILDIM);
        assertThat(detail.getValue().getFeelingSource()).isEqualTo(FeelingSource.EXPLICIT);
        verify(media).attach(visit.getValue(), userId, List.of());
    }

    @Test
    void retryReturnsCanonicalExperienceAndChangedPayloadConflicts() {
        var request = builder().build();
        String fingerprint = service.canonicalize(request, place).fingerprint();
        Visit existing = org.mockito.Mockito.mock(Visit.class);
        UUID visitId = UUID.randomUUID();
        when(existing.getId()).thenReturn(visitId);
        when(existing.getClientPayloadFingerprint()).thenReturn(fingerprint);
        when(visits.findByUserIdAndClientMutationId(userId, request.clientMutationId()))
                .thenReturn(Optional.of(existing));
        when(details.existsById(visitId)).thenReturn(true);
        when(reader.getVisible(visitId, userId)).thenReturn(response);
        assertThat(service.create(userId, request)).isSameAs(response);
        verify(visits, never()).save(any());

        when(existing.getClientPayloadFingerprint()).thenReturn("different");
        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status().value()).isEqualTo(409));
    }

    private void assertChanged(String original, Builder changed) {
        assertThat(service.canonicalize(changed.build(), place).fingerprint()).isNotEqualTo(original);
    }

    private void assertValidation(CreateExperienceV2Request request, String message) {
        assertThatThrownBy(() -> service.canonicalize(request, place))
                .isInstanceOf(ApiException.class).hasMessageContaining(message);
    }

    private CreateExperienceV2Request.Dimension dimension(String key, DimensionStateCode state) {
        return new CreateExperienceV2Request.Dimension(key, state, 1);
    }

    private Builder builder() { return new Builder(placeId); }

    private static final class Builder {
        UUID mutation = UUID.randomUUID();
        final UUID place;
        LocalDate date = LocalDate.of(2026, 9, 16);
        PrimaryExperienceCode primary = PrimaryExperienceCode.GUN_BATIMI;
        String raw;
        OverallFeelingCode feeling = OverallFeelingCode.BAYILDIM;
        CompanionCode companion = CompanionCode.PARTNER;
        TimeOfDayCode time = TimeOfDayCode.EVENING;
        List<VibeCode> vibes = List.of();
        List<PracticalSignalCode> signals = List.of();
        List<CreateExperienceV2Request.Dimension> dimensions = List.of();
        String title;
        TitleSource titleSource = TitleSource.GENERATED;
        String story = "story";
        String tip;
        String memory;
        Visibility visibility = Visibility.PUBLIC;
        List<UUID> media = List.of();

        Builder(UUID place) { this.place = place; }
        Builder date(LocalDate value) { date = value; return this; }
        Builder primary(PrimaryExperienceCode value) { primary = value; return this; }
        Builder raw(String value) { raw = value; return this; }
        Builder feeling(OverallFeelingCode value) { feeling = value; return this; }
        Builder companion(CompanionCode value) { companion = value; return this; }
        Builder time(TimeOfDayCode value) { time = value; return this; }
        Builder vibes(List<VibeCode> value) { vibes = value; return this; }
        Builder signals(List<PracticalSignalCode> value) { signals = value; return this; }
        Builder dimensions(List<CreateExperienceV2Request.Dimension> value) { dimensions = value; return this; }
        Builder title(String value) { title = value; return this; }
        Builder titleSource(TitleSource value) { titleSource = value; return this; }
        Builder story(String value) { story = value; return this; }
        Builder tip(String value) { tip = value; return this; }
        Builder memory(String value) { memory = value; return this; }
        Builder visibility(Visibility value) { visibility = value; return this; }
        Builder media(List<UUID> value) { media = value; return this; }
        CreateExperienceV2Request build() {
            return new CreateExperienceV2Request(mutation, place, date, primary, raw, feeling,
                    companion, time, vibes, signals, dimensions, title, titleSource,
                    story, tip, memory, visibility, media, null);
        }
    }
}
