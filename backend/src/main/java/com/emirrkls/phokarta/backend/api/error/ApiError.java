package com.emirrkls.phokarta.backend.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        OffsetDateTime timestamp, int status, String code, String message,
        String path, String requestId, Map<String, String> fieldErrors,
        String requiredVersion) {

    public ApiError(OffsetDateTime timestamp, int status, String code, String message,
                    String path, String requestId, Map<String, String> fieldErrors) {
        this(timestamp, status, code, message, path, requestId, fieldErrors, null);
    }
}
