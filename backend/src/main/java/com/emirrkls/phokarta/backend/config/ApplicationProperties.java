package com.emirrkls.phokarta.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "phokarta")
public record ApplicationProperties(@DefaultValue("local") @NotBlank String environment) {
}
