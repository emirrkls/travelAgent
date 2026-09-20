package com.emirrkls.phokarta.backend.api.dto;

public record CapabilitiesV2Response(
        boolean profilePrivacyV2Enabled,
        boolean experiencePlanningEnabled,
        boolean experienceAcknowledgementsEnabled,
        boolean mixedCollectionItemsEnabled,
        boolean experienceConversationsEnabled) {}
