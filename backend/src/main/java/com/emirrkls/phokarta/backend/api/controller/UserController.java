package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicUserProfileResponse;
import com.emirrkls.phokarta.backend.api.dto.UserSummaryResponse;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.SocialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
public class UserController {
    private final SocialService socialService;

    public UserController(SocialService socialService) {
        this.socialService = socialService;
    }

    @Operation(summary = "Search users by username or display name")
    @GetMapping("/search")
    public PageResponse<UserSummaryResponse> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return socialService.search(q, SecurityUtils.currentUserId().orElse(null), page, size);
    }

    @Operation(summary = "Public user profile")
    @GetMapping("/{userId}")
    public PublicUserProfileResponse profile(@PathVariable UUID userId) {
        return socialService.publicProfile(userId, SecurityUtils.currentUserId().orElse(null));
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{userId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void follow(@PathVariable UUID userId) {
        socialService.follow(SecurityUtils.requireCurrentUserId(), userId);
    }

    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{userId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable UUID userId) {
        socialService.unfollow(SecurityUtils.requireCurrentUserId(), userId);
    }
}
