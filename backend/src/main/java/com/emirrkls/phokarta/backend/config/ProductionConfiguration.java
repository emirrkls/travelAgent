package com.emirrkls.phokarta.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@EnableConfigurationProperties(ProductionDatabaseProperties.class)
public class ProductionConfiguration {
    public ProductionConfiguration(@Value("${APP_ENVIRONMENT}") String environment,
                                   @Value("${phokarta.media.endpoint:}") String mediaEndpoint,
                                   Environment springEnvironment) {
        if (environment.isBlank()) {
            throw new IllegalStateException("APP_ENVIRONMENT must not be blank");
        }
        if (Arrays.asList(springEnvironment.getActiveProfiles()).contains("dev")) {
            throw new IllegalStateException("The dev and prod profiles must not be active together");
        }
        requireSecureMediaEndpoint(mediaEndpoint);
    }

    private static void requireSecureMediaEndpoint(String value) {
        if (value == null || value.isBlank()) return;
        try {
            URI endpoint = URI.create(value.trim());
            if (!endpoint.isAbsolute() || endpoint.getHost() == null
                    || !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Production media endpoint must be a valid absolute HTTPS URI", ex);
        }
    }
}
