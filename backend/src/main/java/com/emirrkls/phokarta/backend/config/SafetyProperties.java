package com.emirrkls.phokarta.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("phokarta.safety")
public record SafetyProperties(
        @DefaultValue("10") @Min(1) @Max(1000) int reportMaxPerHour,
        @DefaultValue("60") @Min(1) @Max(1000) int blockMaxPerHour) {
}
