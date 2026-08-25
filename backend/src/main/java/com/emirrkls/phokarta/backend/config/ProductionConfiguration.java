package com.emirrkls.phokarta.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@EnableConfigurationProperties(ProductionDatabaseProperties.class)
public class ProductionConfiguration {
    public ProductionConfiguration(@Value("${APP_ENVIRONMENT}") String environment,
                                   Environment springEnvironment) {
        if (environment.isBlank()) {
            throw new IllegalStateException("APP_ENVIRONMENT must not be blank");
        }
        if (Arrays.asList(springEnvironment.getActiveProfiles()).contains("dev")) {
            throw new IllegalStateException("The dev and prod profiles must not be active together");
        }
    }
}
