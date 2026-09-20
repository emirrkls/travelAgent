package com.emirrkls.phokarta.backend.api.dto;

import java.time.OffsetDateTime;

public record PlannedExperienceV2Response(ExperienceV2Response experience, OffsetDateTime plannedAt) {}
