package com.emirrkls.phokarta.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

@Validated
@ConfigurationProperties("phokarta.media")
public record MediaProperties(
        boolean enabled,
        @NotBlank String bucket,
        @NotBlank String region,
        String endpoint,
        boolean pathStyle,
        String accessKey,
        String secretKey,
        @Min(1) long maxBytes,
        @Min(1) @Max(100) int maxPerVisit,
        @NotNull Set<String> acceptedContentTypes,
        @NotNull Duration uploadTtl,
        @NotNull Duration readTtl,
        @NotNull Duration unattachedTtl,
        @Min(1) @Max(100) int cleanupBatchSize,
        @NotNull Duration cleanupInterval,
        @DefaultValue("2m") @NotNull Duration deletionVerifyGrace) {
}
