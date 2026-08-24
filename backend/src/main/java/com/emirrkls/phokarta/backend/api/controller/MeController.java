package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CollectionDetailResponse;
import com.emirrkls.phokarta.backend.api.dto.CollectionSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.CreateCollectionRequest;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.SavedPlaceResponse;
import com.emirrkls.phokarta.backend.api.dto.UserProfileResponse;
import com.emirrkls.phokarta.backend.api.dto.UserSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.AuthService;
import com.emirrkls.phokarta.backend.service.CollectionService;
import com.emirrkls.phokarta.backend.service.SavedPlaceService;
import com.emirrkls.phokarta.backend.service.SocialService;
import com.emirrkls.phokarta.backend.service.VisitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/me")
@Tag(name = "Current user")
@SecurityRequirement(name = "bearerAuth")
public class MeController {
    private final AuthService authService;
    private final VisitService visitService;
    private final SavedPlaceService savedPlaceService;
    private final CollectionService collectionService;
    private final SocialService socialService;

    public MeController(AuthService authService, VisitService visitService,
                        SavedPlaceService savedPlaceService, CollectionService collectionService,
                        SocialService socialService) {
        this.authService = authService;
        this.visitService = visitService;
        this.savedPlaceService = savedPlaceService;
        this.collectionService = collectionService;
        this.socialService = socialService;
    }

    @GetMapping
    public UserProfileResponse me() {
        return authService.me(SecurityUtils.requireCurrentUserId());
    }

    @GetMapping("/visits")
    public PageResponse<VisitOwnerResponse> myVisits(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return visitService.ownerVisits(SecurityUtils.requireCurrentUserId(), page, size);
    }

    @Operation(summary = "List saved places",
            description = "Owner-only Want to Go list, newest saved first. Each row includes "
                    + "community Place summary (PUBLIC Visits) plus viewer-relative friend "
                    + "overlap: friendsVisitedCount and friendAverageScore from mutual friends' "
                    + "friend-readable Visits (PUBLIC or FRIENDS). PRIVATE Visits never contribute. "
                    + "friendAverageScore is omitted when count is 0.")
    @GetMapping("/saved-places")
    public PageResponse<SavedPlaceResponse> mySavedPlaces(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return savedPlaceService.list(SecurityUtils.requireCurrentUserId(), page, size);
    }

    @PostMapping("/saved-places/{placeId}")
    public SavedPlaceResponse savePlace(@PathVariable UUID placeId) {
        return savedPlaceService.save(SecurityUtils.requireCurrentUserId(), placeId);
    }

    @DeleteMapping("/saved-places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSavedPlace(@PathVariable UUID placeId) {
        savedPlaceService.remove(SecurityUtils.requireCurrentUserId(), placeId);
    }

    @GetMapping("/collections")
    public PageResponse<CollectionSummaryResponse> myCollections(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return collectionService.list(SecurityUtils.requireCurrentUserId(), page, size);
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionDetailResponse createCollection(
            @Valid @RequestBody CreateCollectionRequest request) {
        return collectionService.create(SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Users who follow the current user (newest first)")
    @GetMapping("/followers")
    public PageResponse<UserSummaryResponse> followers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return socialService.followers(SecurityUtils.requireCurrentUserId(), page, size);
    }

    @Operation(summary = "Users the current user follows (newest first)")
    @GetMapping("/following")
    public PageResponse<UserSummaryResponse> following(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return socialService.following(SecurityUtils.requireCurrentUserId(), page, size);
    }

    @Operation(summary = "Mutual follows (friends), alphabetical by display name")
    @GetMapping("/friends")
    public PageResponse<UserSummaryResponse> friends(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return socialService.friends(SecurityUtils.requireCurrentUserId(), page, size);
    }
}
