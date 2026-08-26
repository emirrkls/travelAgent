package com.emirrkls.phokarta.backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("phokarta.policy")
public record PolicyProperties(
        @DefaultValue("2026-08-beta")
        @NotBlank
        @Size(max = 64)
        String requiredVersion) {
}
