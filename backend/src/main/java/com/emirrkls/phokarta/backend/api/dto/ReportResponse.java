package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ReportReason;
import com.emirrkls.phokarta.backend.domain.model.ReportStatus;
import com.emirrkls.phokarta.backend.domain.model.ReportTargetType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        ReportTargetType targetType,
        ReportReason reason,
        ReportStatus status,
        OffsetDateTime createdAt
) {
}
