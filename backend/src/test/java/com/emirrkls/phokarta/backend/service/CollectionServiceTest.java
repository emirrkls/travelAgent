package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.api.mapper.PlaceMapper;
import com.emirrkls.phokarta.backend.domain.entity.Collection;
import com.emirrkls.phokarta.backend.domain.entity.CollectionPlaceId;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.repository.CollectionPlaceRepository;
import com.emirrkls.phokarta.backend.repository.CollectionRepository;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

    @Mock private CollectionRepository collections;
    @Mock private CollectionPlaceRepository memberships;
    @Mock private PlaceRepository places;
    @Mock private UserRepository users;
    @Mock private PlaceMapper mapper;
    @Mock private Collection collection;
    @Mock private User owner;

    @Test
    void rejectsAddingAPlaceAlreadyInCollection() {
        UUID collectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        when(collections.findByIdForUpdate(collectionId)).thenReturn(Optional.of(collection));
        when(collection.getUser()).thenReturn(owner);
        when(owner.getId()).thenReturn(userId);
        when(memberships.existsById(new CollectionPlaceId(collectionId, placeId))).thenReturn(true);

        CollectionService service =
                new CollectionService(collections, memberships, places, users, mapper);

        assertThatThrownBy(() -> service.add(collectionId, userId, placeId))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.getMessage()).isEqualTo(
                            "Place is already in this collection");
                });
        verify(places, never()).findById(placeId);
        verify(memberships, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removingAPlaceLocksAndTouchesTheCollection() {
        UUID collectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        CollectionPlaceId membershipId = new CollectionPlaceId(collectionId, placeId);
        when(collections.findByIdForUpdate(collectionId)).thenReturn(Optional.of(collection));
        when(collection.getUser()).thenReturn(owner);
        when(owner.getId()).thenReturn(userId);
        when(memberships.existsById(membershipId)).thenReturn(true);

        CollectionService service =
                new CollectionService(collections, memberships, places, users, mapper);

        service.remove(collectionId, userId, placeId);

        verify(memberships).deleteById(membershipId);
        verify(collection).touch(org.mockito.ArgumentMatchers.any(OffsetDateTime.class));
    }
}
