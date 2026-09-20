package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.domain.entity.*;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import com.emirrkls.phokarta.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperienceAcknowledgementServiceTest {
    @Mock ExperienceAcknowledgementRepository acknowledgements;
    @Mock UserRepository users;
    @Mock VisitRepository visits;
    @Mock VisitExperienceDetailRepository details;
    @Mock ViewerAccessPolicy access;
    @Mock ExperienceReadService reader;
    @Mock UgcPolicyService ugcPolicy;
    @Mock PlaceRepository places;
    @Mock User user;
    @Mock User author;
    @Mock Visit source;
    @Mock Place place;
    @Mock VisitExperienceDetail detail;
    ExperienceAcknowledgementService service;
    UUID userId;
    UUID sourceId;

    @BeforeEach void setUp() {
        service = new ExperienceAcknowledgementService(
                acknowledgements, users, visits, details, access, reader, ugcPolicy, places);
        userId = UUID.randomUUID(); sourceId = UUID.randomUUID();
    }

    @Test void createsOneDurableAnchorWithoutCreatingAVisit() {
        UUID authorId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(visits.findDetailedById(sourceId)).thenReturn(Optional.of(source));
        when(source.getUser()).thenReturn(author);
        when(author.getId()).thenReturn(authorId);
        when(source.getPlace()).thenReturn(place);
        when(access.canViewVisit(source, userId)).thenReturn(true);
        when(details.findById(sourceId)).thenReturn(Optional.of(detail));
        when(detail.getPrimaryExperienceCode()).thenReturn(PrimaryExperienceCode.GUN_BATIMI);
        when(acknowledgements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(user.getId()).thenReturn(userId);

        var result = service.acknowledge(userId, sourceId);

        assertThat(result.ownerUserId()).isEqualTo(userId);
        assertThat(result.primaryExperienceCode()).isEqualTo(PrimaryExperienceCode.GUN_BATIMI);
        verify(acknowledgements).save(any(ExperienceAcknowledgement.class));
        verify(visits).findDetailedById(sourceId);
        verifyNoMoreInteractions(visits);
    }

    @Test void preservesClientGeneratedIdForOfflineConversion() {
        UUID clientId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(visits.findDetailedById(sourceId)).thenReturn(Optional.of(source));
        when(source.getUser()).thenReturn(author);
        when(author.getId()).thenReturn(UUID.randomUUID());
        when(source.getPlace()).thenReturn(place);
        when(access.canViewVisit(source, userId)).thenReturn(true);
        when(details.findById(sourceId)).thenReturn(Optional.of(detail));
        when(detail.getPrimaryExperienceCode()).thenReturn(PrimaryExperienceCode.GUN_BATIMI);
        when(acknowledgements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(user.getId()).thenReturn(userId);

        var result = service.acknowledge(userId, sourceId, clientId);

        assertThat(result.id()).isEqualTo(clientId);
    }

    @Test void duplicateRequestReturnsExistingAndDoesNotInflateCount() {
        var existing = mock(ExperienceAcknowledgement.class);
        when(acknowledgements.findByUserIdAndSourceExperienceId(userId, sourceId))
                .thenReturn(Optional.of(existing));
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(existing.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(existing.getAcknowledgedAt()).thenReturn(OffsetDateTime.now());
        when(existing.getPlace()).thenReturn(place);
        when(existing.getPrimaryExperienceCode()).thenReturn(PrimaryExperienceCode.GUN_BATIMI);

        service.acknowledge(userId, sourceId);

        verify(acknowledgements, never()).save(any());
        verify(visits, never()).findDetailedById(any());
    }

    @Test void clientIdRetryReturnsDurableTombstoneAfterSourceDeletion() {
        UUID clientId = UUID.randomUUID();
        var existing = mock(ExperienceAcknowledgement.class);
        when(acknowledgements.findByIdForUpdate(clientId)).thenReturn(Optional.of(existing));
        when(existing.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(existing.getId()).thenReturn(clientId);
        when(existing.getPlace()).thenReturn(place);
        when(existing.getPrimaryExperienceCode()).thenReturn(PrimaryExperienceCode.GUN_BATIMI);
        when(existing.getAcknowledgedAt()).thenReturn(OffsetDateTime.now());

        assertThat(service.acknowledge(userId, sourceId, clientId).id()).isEqualTo(clientId);
        verify(visits, never()).findDetailedById(any());
        verify(acknowledgements, never()).save(any());
    }

    @Test void offlineAnchorCanPersistWithoutDeletedSourceContent() {
        UUID clientId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(userId);
        when(visits.findDetailedById(sourceId)).thenReturn(Optional.empty());
        when(places.findById(placeId)).thenReturn(Optional.of(place));
        when(place.getId()).thenReturn(placeId);
        when(acknowledgements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.acknowledge(userId, sourceId, clientId,
                placeId, PrimaryExperienceCode.GUN_BATIMI, null);

        assertThat(result.id()).isEqualTo(clientId);
        assertThat(result.sourceAvailable()).isFalse();
        assertThat(result.sourceExperience()).isNull();
        assertThat(result.place().id()).isEqualTo(placeId);
        assertThat(result.primaryExperienceCode()).isEqualTo(PrimaryExperienceCode.GUN_BATIMI);
    }

    @Test void selfAcknowledgementIsRejected() {
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(visits.findDetailedById(sourceId)).thenReturn(Optional.of(source));
        when(source.getUser()).thenReturn(author);
        when(author.getId()).thenReturn(userId);
        assertThatThrownBy(() -> service.acknowledge(userId, sourceId))
                .isInstanceOf(com.emirrkls.phokarta.backend.api.error.ApiException.class);
        verify(acknowledgements, never()).save(any());
    }

    @Test void inaccessibleSourceIsNotDisclosed() {
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(visits.findDetailedById(sourceId)).thenReturn(Optional.of(source));
        when(source.getUser()).thenReturn(author);
        when(author.getId()).thenReturn(UUID.randomUUID());
        when(access.canViewVisit(source, userId)).thenReturn(false);
        assertThatThrownBy(() -> service.acknowledge(userId, sourceId))
                .isInstanceOf(com.emirrkls.phokarta.backend.api.error.ApiException.class);
        verify(details, never()).findById(any());
    }
}
