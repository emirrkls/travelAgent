package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.mapper.PlaceMapper;
import com.emirrkls.phokarta.backend.domain.entity.SavedPlace;
import com.emirrkls.phokarta.backend.domain.entity.SavedPlaceId;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.SavedPlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedPlaceServiceTest {

    @Mock private SavedPlaceRepository saved;
    @Mock private UserRepository users;
    @Mock private PlaceRepository places;
    @Mock private VisitRepository visits;
    @Mock private PlaceMapper mapper;
    @Mock private SavedPlace existing;

    @Test
    void savingAnExistingPlaceIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        OffsetDateTime originalSavedAt = OffsetDateTime.parse("2025-04-01T10:15:30Z");
        SavedPlaceId id = new SavedPlaceId(userId, placeId);
        when(users.existsById(userId)).thenReturn(true);
        when(places.existsById(placeId)).thenReturn(true);
        when(saved.findDetailedById(id)).thenReturn(Optional.of(existing));
        when(existing.getSavedAt()).thenReturn(originalSavedAt);

        var response = new SavedPlaceService(saved, users, places, visits, mapper).save(userId, placeId);

        assertThat(response.savedAt()).isEqualTo(originalSavedAt);
        assertThat(response.friendsVisitedCount()).isZero();
        assertThat(response.friendAverageScore()).isNull();
        verify(saved, never()).save(org.mockito.ArgumentMatchers.any());
        verify(saved).insertIfAbsent(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(placeId),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class));
    }
}
