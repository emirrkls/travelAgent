package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;

import java.util.UUID;

/** Nullable metrics are intentionally absent for identity-only private-profile reads. */
public record ProfileV2Response(
        UUID id,
        String username,
        String displayName,
        String avatarUrl,
        String bio,
        ProfileVisibility profileVisibility,
        boolean fullProfile,
        RelationshipV2Response relationship,
        Integer cityCount,
        Integer countryCount,
        Long followerCount,
        Long followingCount,
        Long friendCount,
        Long visibleExperienceCount) {}
