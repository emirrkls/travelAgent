package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;

import java.util.UUID;

/** Search-safe identity fields only; protected metrics cannot be serialized here. */
public record ProfileSummaryV2Response(
        UUID id,
        String username,
        String displayName,
        String avatarUrl,
        String bio,
        ProfileVisibility profileVisibility,
        RelationshipV2Response relationship) {}
