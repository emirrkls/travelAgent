package com.emirrkls.phokarta.backend.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "phokarta.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:3000", "http://localhost:5173"})
        @NotEmpty List<String> allowedOrigins) {

    @AssertTrue(message = "allowed-origins must contain explicit http(s) origins without paths")
    public boolean isValidAllowlist() {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return false;
        }
        return allowedOrigins.stream().allMatch(CorsProperties::isOrigin);
    }

    private static boolean isOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && !value.contains("*");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
