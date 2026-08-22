package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.mapper.VisitMapper;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.domain.service.RatingDimensionRegistry;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitServiceTest {

    @Mock private VisitRepository visits;
    @Mock private VisitDimensionScoreRepository scores;
    @Mock private UserRepository users;
    @Mock private PlaceRepository places;
    @Mock private VisitMapper mapper;
    @Mock private User user;
    @Mock private Place place;

    private VisitService service;
    private UUID userId;
    private UUID placeId;

    @BeforeEach
    void setUp() {
        service = new VisitService(visits, scores, users, places,
                new RatingDimensionRegistry(), mapper);
        userId = UUID.randomUUID();
        placeId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(places.findById(placeId)).thenReturn(Optional.of(place));
    }

    @Test
    void createsEveryVisitAsANewAppend() {
        CreateVisitRequest request = request(validBeachScores());
        when(place.getCategory()).thenReturn(PlaceCategory.BEACH);
        when(visits.save(any(Visit.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(request);
        service.create(request);

        ArgumentCaptor<Visit> captor = ArgumentCaptor.forClass(Visit.class);
        verify(visits, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Visit::getId)
                .doesNotHaveDuplicates();
        assertThat(captor.getAllValues())
                .extracting(Visit::getVerificationStatus)
                .containsOnly(VerificationStatus.UNVERIFIED);
        verify(scores, org.mockito.Mockito.times(2)).saveAll(any());
    }

    @Test
    void rejectsDuplicateDimensionKeysBeforeSavingVisit() {
        List<CreateVisitRequest.DimensionScore> duplicate = List.of(
                new CreateVisitRequest.DimensionScore("SEA", 9.0),
                new CreateVisitRequest.DimensionScore("SEA", 8.0));

        assertThatThrownBy(() -> service.create(request(duplicate)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("Duplicate dimension key: SEA");
                });

        verify(visits, never()).save(any());
        verify(scores, never()).saveAll(any());
    }

    @Test
    void acceptsVisitWithZeroDimensions() {
        when(place.getCategory()).thenReturn(PlaceCategory.BEACH);
        when(visits.save(any(Visit.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(request(List.of()));

        verify(visits).save(any(Visit.class));
        verify(scores).saveAll(List.of());
    }

    private CreateVisitRequest request(List<CreateVisitRequest.DimensionScore> dimensions) {
        return new CreateVisitRequest(userId, placeId, LocalDate.of(2025, 8, 1), 8.5,
                dimensions, "review", "memory", List.of(), Visibility.PUBLIC);
    }

    private List<CreateVisitRequest.DimensionScore> validBeachScores() {
        return List.of(
                new CreateVisitRequest.DimensionScore("SEA", 9.0),
                new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0));
    }
}
