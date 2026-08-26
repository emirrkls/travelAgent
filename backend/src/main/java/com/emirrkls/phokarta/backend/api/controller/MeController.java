package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.BlockedUserResponse;
import com.emirrkls.phokarta.backend.api.dto.CollectionDetailResponse;
import com.emirrkls.phokarta.backend.api.dto.CollectionSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.CreateCollectionRequest;
import com.emirrkls.phokarta.backend.api.dto.DeleteAccountRequest;
import com.emirrkls.phokarta.backend.api.dto.FriendPlaceMetricsRequest;
import com.emirrkls.phokarta.backend.api.dto.FriendPlaceMetricsResponse;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PolicyAcceptanceRequest;
import com.emirrkls.phokarta.backend.api.dto.PolicyStatusResponse;
import com.emirrkls.phokarta.backend.api.dto.SavedPlaceResponse;
import com.emirrkls.phokarta.backend.api.dto.UserProfileResponse;
import com.emirrkls.phokarta.backend.api.dto.UserSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.security.AuthRateLimiter;
import com.emirrkls.phokarta.backend.security.SafetyRateLimiter;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.AccountDeletionService;
import com.emirrkls.phokarta.backend.service.AuthService;
import com.emirrkls.phokarta.backend.service.BlockService;
import com.emirrkls.phokarta.backend.service.CollectionService;
import com.emirrkls.phokarta.backend.service.SavedPlaceService;
import com.emirrkls.phokarta.backend.service.SocialService;
import com.emirrkls.phokarta.backend.service.UgcPolicyService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
    private final BlockService blockService;
    private final AccountDeletionService accountDeletionService;
    private final UgcPolicyService ugcPolicyService;
    private final AuthRateLimiter rateLimiter;
    private final SafetyRateLimiter safetyRateLimiter;

    public MeController(AuthService authService, VisitService visitService,
                        SavedPlaceService savedPlaceService, CollectionService collectionService,
                        SocialService socialService, BlockService blockService,
                        AccountDeletionService accountDeletionService,
                        UgcPolicyService ugcPolicyService,
                        AuthRateLimiter rateLimiter, SafetyRateLimiter safetyRateLimiter) {
        this.authService = authService;
        this.visitService = visitService;
        this.savedPlaceService = savedPlaceService;
        this.collectionService = collectionService;
        this.socialService = socialService;
        this.blockService = blockService;
        this.accountDeletionService = accountDeletionService;
        this.ugcPolicyService = ugcPolicyService;
        this.rateLimiter = rateLimiter;
        this.safetyRateLimiter = safetyRateLimiter;
    }

    @GetMapping
    public UserProfileResponse me() {
        return authService.me(SecurityUtils.requireCurrentUserId());
    }

    @Operation(summary = "Current User Policy acceptance status",
            description = "Backend is authoritative. accepted is true only when the caller has "
                    + "accepted the currently required technical policy version. An older "
                    + "acceptance does not satisfy a newer required version.")
    @GetMapping("/policy-status")
    public PolicyStatusResponse policyStatus() {
        return ugcPolicyService.status(SecurityUtils.requireCurrentUserId());
    }

    @Operation(summary = "Accept the current User Policy version",
            description = "Only the currently required version is accepted. Duplicate posts of "
                    + "that version are idempotent. Arbitrary or future versions are rejected. "
                    + "This is a technical beta gate, not a claim that legal Terms are final.")
    @PostMapping("/policy-acceptance")
    public PolicyStatusResponse acceptPolicy(@Valid @RequestBody PolicyAcceptanceRequest request) {
        return ugcPolicyService.accept(SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Permanently delete the authenticated account",
            description = "Hard-deletes the caller's identity and all user-owned product data. "
                    + "Password accounts must send currentPassword. Object-storage bytes are "
                    + "removed asynchronously after the account becomes inaccessible.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@Valid @RequestBody(required = false) DeleteAccountRequest request) {
        var userId = SecurityUtils.requireCurrentUserId();
        rateLimiter.check("account_delete", userId.toString());
        accountDeletionService.deleteCurrentAccount(userId, request);
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
    @Operation(summary = "Set saved state to saved",
            description = "Idempotent desired-state operation. Saving an already-saved Place succeeds and returns the current saved row.")
    public SavedPlaceResponse savePlace(@PathVariable UUID placeId) {
        return savedPlaceService.save(SecurityUtils.requireCurrentUserId(), placeId);
    }

    @DeleteMapping("/saved-places/{placeId}")
    @Operation(summary = "Set saved state to not saved",
            description = "Idempotent desired-state operation. Removing an already-absent saved Place succeeds.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSavedPlace(@PathVariable UUID placeId) {
        savedPlaceService.remove(SecurityUtils.requireCurrentUserId(), placeId);
    }

    @Operation(summary = "Batch friend metrics for places",
            description = "Authenticated viewer-relative aggregates for the given Place IDs: "
                    + "friendsVisitedCount (distinct mutual friends with ≥1 friend-readable Visit) "
                    + "and friendAverageScore (AVG of per-friend averages). Qualifying Visits are "
                    + "PUBLIC or FRIENDS; PRIVATE and self are excluded. One-way follows do not count. "
                    + "Does not include friend identities, review text, or privateMemory. "
                    + "Public Place DTOs stay community-only; this companion avoids polluting bounds. "
                    + "At most 200 IDs. Duplicates are de-duplicated. Empty list returns [].")
    @PostMapping("/places/friend-metrics")
    public List<FriendPlaceMetricsResponse> friendMetrics(
            @Valid @RequestBody FriendPlaceMetricsRequest request) {
        return visitService.friendMetrics(
                SecurityUtils.requireCurrentUserId(), request.placeIds());
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

    @Operation(summary = "Accounts the current user has blocked",
            description = "Does not include users who blocked the caller. Pageable.")
    @GetMapping("/blocks")
    public PageResponse<BlockedUserResponse> blockedUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return blockService.listBlocked(SecurityUtils.requireCurrentUserId(), page, size);
    }

    @Operation(summary = "Block a user",
            description = "Idempotent. Removes follow edges in both directions in the same "
                    + "transaction. Does not notify the target.")
    @PutMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@PathVariable UUID userId) {
        var blockerId = SecurityUtils.requireCurrentUserId();
        safetyRateLimiter.checkBlock(blockerId);
        blockService.block(blockerId, userId);
    }

    @Operation(summary = "Unblock a user",
            description = "Idempotent. Does not restore previous follow edges.")
    @DeleteMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable UUID userId) {
        var blockerId = SecurityUtils.requireCurrentUserId();
        safetyRateLimiter.checkBlock(blockerId);
        blockService.unblock(blockerId, userId);
    }
}
