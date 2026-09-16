package com.emirrkls.phokarta.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "phokarta.features.profile-privacy-v2")
public record ProfilePrivacyProperties(@DefaultValue("false") boolean enabled) {}
