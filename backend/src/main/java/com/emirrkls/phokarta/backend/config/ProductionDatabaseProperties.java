package com.emirrkls.phokarta.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "spring.datasource")
public record ProductionDatabaseProperties(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password) {
}
