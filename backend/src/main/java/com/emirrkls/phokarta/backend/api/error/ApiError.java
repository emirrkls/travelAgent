package com.emirrkls.phokarta.backend.api.error;

import java.time.OffsetDateTime;
import java.util.Map;

public record ApiError(
        OffsetDateTime timestamp, int status, String code, String message,
        String path, Map<String, String> fieldErrors) {
}
