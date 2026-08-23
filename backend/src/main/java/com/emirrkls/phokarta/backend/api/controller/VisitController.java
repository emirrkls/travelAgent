package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.FriendPlaceSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicActivityResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicVisitResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.domain.model.FeedScope;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.VisitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
public class VisitController {
    private final VisitService service;

    public VisitController(VisitService service) {
        this.service = service;
    }

    @Operation(summary = "Append a visit",
            description = "Visits are append-only. Ownership is taken from the authenticated principal.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitOwnerResponse create(@Valid @RequestBody CreateVisitRequest request) {
        return service.create(SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Public reviews for a place",
            description = "Returns PUBLIC visits only. scope=community (default) is public; "
                    + "scope=friends requires auth and returns mutual-friend Visits only. "
                    + "privateMemory is never included.")
    @GetMapping("/places/{placeId}/reviews")
    public PageResponse<PublicVisitResponse> publicReviews(
            @PathVariable UUID placeId,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.publicReviews(placeId, FeedScope.fromParam(scope), page, size);
    }

    @Operation(summary = "Friends discovery summary for a place",
            description = "Authenticated viewer-relative summary: user-weighted friends score, "
                    + "unique friends visited, and a small preview ordered by latest Visit. "
                    + "Does not pollute the public Place detail response.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/places/{placeId}/friends-summary")
    public FriendPlaceSummaryResponse friendsSummary(@PathVariable UUID placeId) {
        return service.friendsSummary(placeId, SecurityUtils.requireCurrentUserId());
    }

    @Operation(summary = "Activity feed",
            description = "Cross-place PUBLIC visit events, newest first. "
                    + "scope=community (default, public) or scope=friends (auth, mutual friends only). "
                    + "privateMemory and private user fields are never included.")
    @GetMapping("/activity")
    public PageResponse<PublicActivityResponse> publicActivity(
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.publicActivity(FeedScope.fromParam(scope), page, size);
    }
}
