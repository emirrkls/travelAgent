package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.domain.entity.PlannedExperience;
import com.emirrkls.phokarta.backend.domain.entity.PlannedExperienceId;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.repository.PlannedExperienceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperiencePlanServiceTest {
    @Mock PlannedExperienceRepository plans;
    @Mock UserRepository users;
    @Mock VisitRepository visits;
    @Mock ViewerAccessPolicy access;
    @Mock ExperienceReadService reader;
    @Mock UgcPolicyService ugcPolicy;
    @Mock User user;
    @Mock Visit experience;
    @Mock ExperienceV2Response response;
    ExperiencePlanService service;
    UUID userId;
    UUID experienceId;

    @BeforeEach void setUp() {
        service = new ExperiencePlanService(plans, users, visits, access, reader, ugcPolicy);
        userId = UUID.randomUUID(); experienceId = UUID.randomUUID();
    }

    @Test void duplicateSaveReturnsExistingWithoutCreatingAnotherMembership() {
        var existing = new PlannedExperience(user, experience, OffsetDateTime.now());
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(visits.findDetailedById(experienceId)).thenReturn(Optional.of(experience));
        when(access.canViewVisit(experience, userId)).thenReturn(true);
        when(plans.findById(new PlannedExperienceId(userId, experienceId))).thenReturn(Optional.of(existing));
        when(reader.getVisible(experienceId, userId)).thenReturn(response);

        service.save(userId, experienceId);

        verify(plans, never()).save(any());
    }

    @Test void inaccessibleSourceCannotBePlanned() {
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(visits.findDetailedById(experienceId)).thenReturn(Optional.of(experience));
        when(access.canViewVisit(experience, userId)).thenReturn(false);
        assertThatThrownBy(() -> service.save(userId, experienceId))
                .isInstanceOf(com.emirrkls.phokarta.backend.api.error.ApiException.class);
        verify(plans, never()).save(any());
    }

    @Test void removeIsIdempotentAndAccountScoped() {
        service.remove(userId, experienceId);
        verify(users).lockAccount(userId);
        verify(plans).deleteById(new PlannedExperienceId(userId, experienceId));
    }
}
