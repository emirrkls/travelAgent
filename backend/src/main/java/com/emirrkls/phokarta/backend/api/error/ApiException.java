package com.emirrkls.phokarta.backend.api.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String requiredVersion;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, String requiredVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.requiredVersion = requiredVersion;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String requiredVersion() { return requiredVersion; }

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                resource + " not found: " + id);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException policyAcceptanceRequired(String requiredVersion) {
        return new ApiException(HttpStatus.FORBIDDEN, "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current user policy before creating or uploading content",
                requiredVersion);
    }
}
