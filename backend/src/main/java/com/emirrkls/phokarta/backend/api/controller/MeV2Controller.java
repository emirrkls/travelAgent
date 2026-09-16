package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.FollowRequestV2Response;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.ProfileV2Response;
import com.emirrkls.phokarta.backend.api.dto.ProfileVisibilityUpdateRequest;
import com.emirrkls.phokarta.backend.api.dto.RelationshipV2Response;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.FollowRequestService;
import com.emirrkls.phokarta.backend.service.ProfileV2Service;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v2/me")
public class MeV2Controller {
    private final ProfileV2Service profiles;
    private final FollowRequestService followRequests;

    public MeV2Controller(ProfileV2Service profiles, FollowRequestService followRequests) {
        this.profiles = profiles;
        this.followRequests = followRequests;
    }

    @PutMapping("/profile-visibility")
    public ProfileV2Response updateVisibility(
            @Valid @RequestBody ProfileVisibilityUpdateRequest request) {
        return profiles.updateVisibility(SecurityUtils.requireCurrentUserId(), request.visibility());
    }

    @GetMapping("/follow-requests")
    public PageResponse<FollowRequestV2Response> incoming(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return followRequests.incoming(SecurityUtils.requireCurrentUserId(), page, size);
    }

    @PostMapping("/follow-requests/{requestId}/approve")
    public RelationshipV2Response approve(@PathVariable UUID requestId) {
        return followRequests.approve(SecurityUtils.requireCurrentUserId(), requestId);
    }

    @PostMapping("/follow-requests/{requestId}/reject")
    public RelationshipV2Response reject(@PathVariable UUID requestId) {
        return followRequests.reject(SecurityUtils.requireCurrentUserId(), requestId);
    }
}
