package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigurationTest {

    @Test
    void productionContextFailsFastForBlankDatabaseCredentials() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "APP_ENVIRONMENT=production-test",
                        "spring.datasource.url=",
                        "spring.datasource.username=",
                        "spring.datasource.password=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionContextRejectsDevProfileCombination() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod,dev",
                        "APP_ENVIRONMENT=production-test",
                        "spring.datasource.url=jdbc:postgresql://db/phokarta",
                        "spring.datasource.username=app",
                        "spring.datasource.password=secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void jwtConfigurationFailsWithoutSigningSecret() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtBindingConfiguration.class)
                .withPropertyValues(
                        "phokarta.jwt.access-token-ttl=15m",
                        "phokarta.jwt.refresh-token-ttl=30d")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtBindingConfiguration {
    }
}
