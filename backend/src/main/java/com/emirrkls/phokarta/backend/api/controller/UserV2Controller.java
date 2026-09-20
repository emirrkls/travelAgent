package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.ProfileSummaryV2Response;
import com.emirrkls.phokarta.backend.api.dto.ProfileV2Response;
import com.emirrkls.phokarta.backend.api.dto.RelationshipV2Response;
import com.emirrkls.phokarta.backend.api.dto.CursorPageResponse;
import com.emirrkls.phokarta.backend.api.dto.ExperienceSummaryV2Response;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.FollowRequestService;
import com.emirrkls.phokarta.backend.service.ProfileV2Service;
import com.emirrkls.phokarta.backend.service.ExperienceFeedService;
import com.emirrkls.phokarta.backend.service.ExperienceAcknowledgementService;
import com.emirrkls.phokarta.backend.api.dto.ExperienceAcknowledgementV2Response;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v2/users")
public class UserV2Controller {
    private final ProfileV2Service profiles;
    private final FollowRequestService followRequests;
    private final ExperienceFeedService experiences;
    private final ExperienceAcknowledgementService acknowledgements;

    public UserV2Controller(ProfileV2Service profiles, FollowRequestService followRequests,
                            ExperienceFeedService experiences,
                            ExperienceAcknowledgementService acknowledgements) {
        this.profiles = profiles;
        this.followRequests = followRequests;
        this.experiences = experiences;
        this.acknowledgements = acknowledgements;
    }

    @GetMapping("/search")
    public PageResponse<ProfileSummaryV2Response> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return profiles.search(q, SecurityUtils.currentUserId().orElse(null), page, size);
    }

    @GetMapping("/{userId}")
    public ProfileV2Response profile(@PathVariable UUID userId) {
        return profiles.profile(userId, SecurityUtils.currentUserId().orElse(null));
    }

    @GetMapping("/{userId}/experiences")
    public CursorPageResponse<ExperienceSummaryV2Response> experiences(
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        profiles.profile(userId, SecurityUtils.currentUserId().orElse(null));
        return experiences.forProfile(userId, SecurityUtils.currentUserId().orElse(null),
                cursor, size);
    }

    @GetMapping("/{userId}/experience-acknowledgements")
    public PageResponse<ExperienceAcknowledgementV2Response> acknowledgements(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        profiles.profile(userId, SecurityUtils.currentUserId().orElse(null));
        return acknowledgements.listUnconverted(userId, SecurityUtils.currentUserId().orElse(null), page, size);
    }

    @PostMapping("/{userId}/follow")
    public RelationshipV2Response follow(@PathVariable UUID userId) {
        return followRequests.follow(SecurityUtils.requireCurrentUserId(), userId);
    }

    @DeleteMapping("/{userId}/follow")
    public RelationshipV2Response unfollow(@PathVariable UUID userId) {
        return followRequests.unfollow(SecurityUtils.requireCurrentUserId(), userId);
    }

    @DeleteMapping("/{userId}/follow-request")
    public RelationshipV2Response cancel(@PathVariable UUID userId) {
        return followRequests.cancel(SecurityUtils.requireCurrentUserId(), userId);
    }
}
