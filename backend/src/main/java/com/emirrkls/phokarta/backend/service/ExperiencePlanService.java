package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PlannedExperienceV2Response;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.PlannedExperience;
import com.emirrkls.phokarta.backend.domain.entity.PlannedExperienceId;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.repository.PlannedExperienceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

@Service
public class ExperiencePlanService {
    private final PlannedExperienceRepository plans;
    private final UserRepository users;
    private final VisitRepository visits;
    private final ViewerAccessPolicy access;
    private final ExperienceReadService reader;
    private final UgcPolicyService ugcPolicy;

    public ExperiencePlanService(PlannedExperienceRepository plans, UserRepository users,
            VisitRepository visits, ViewerAccessPolicy access, ExperienceReadService reader,
            UgcPolicyService ugcPolicy) {
        this.plans = plans; this.users = users; this.visits = visits; this.access = access;
        this.reader = reader; this.ugcPolicy = ugcPolicy;
    }

    @Transactional
    public PlannedExperienceV2Response save(UUID userId, UUID experienceId) {
        users.lockAccount(userId);
        ugcPolicy.requireAccepted(userId);
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        Visit experience = visits.findDetailedById(experienceId)
                .orElseThrow(() -> ApiException.notFound("Experience", experienceId));
        if (!access.canViewVisit(experience, userId)) throw ApiException.notFound("Experience", experienceId);
        PlannedExperienceId id = new PlannedExperienceId(userId, experienceId);
        PlannedExperience value = plans.findById(id).orElse(null);
        if (value == null) value = plans.save(new PlannedExperience(user, experience, OffsetDateTime.now(ZoneOffset.UTC)));
        return new PlannedExperienceV2Response(reader.getVisible(experienceId, userId), value.getPlannedAt());
    }

    @Transactional
    public void remove(UUID userId, UUID experienceId) {
        users.lockAccount(userId);
        plans.deleteById(new PlannedExperienceId(userId, experienceId));
    }

    @Transactional(readOnly = true)
    public PageResponse<PlannedExperienceV2Response> list(UUID userId, int page, int size) {
        if (!users.existsById(userId)) throw ApiException.notFound("User", userId);
        var source = plans.findByIdUserIdOrderByPlannedAtDesc(userId, PageRequest.of(page, size));
        var content = new ArrayList<PlannedExperienceV2Response>();
        for (PlannedExperience value : source.getContent()) {
            if (access.canViewVisit(value.getExperience(), userId)) {
                content.add(new PlannedExperienceV2Response(
                        reader.getVisible(value.getExperience().getId(), userId), value.getPlannedAt()));
            }
        }
        return new PageResponse<>(content, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages(), source.hasNext());
    }
}
