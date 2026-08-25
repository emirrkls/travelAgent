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
    void productionContextRejectsHttpMediaEndpoint() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionConfiguration.class)
                .withPropertyValues(validProductionProperties())
                .withPropertyValues("phokarta.media.endpoint=http://minio:9000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionContextAcceptsHttpsMediaEndpoint() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionConfiguration.class)
                .withPropertyValues(validProductionProperties())
                .withPropertyValues("phokarta.media.endpoint=https://objects.example.test")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionContextAcceptsDefaultAwsEndpoint() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionConfiguration.class)
                .withPropertyValues(validProductionProperties())
                .run(context -> assertThat(context).hasNotFailed());
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

    @Test
    void mediaConfigurationBindsOptionalEndpointAndCredentials() {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaStorageConfig.class)
                .withPropertyValues(validMediaProperties())
                .withPropertyValues(
                        "phokarta.media.endpoint=http://minio:9000",
                        "phokarta.media.access-key=local",
                        "phokarta.media.secret-key=local-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaProperties properties = context.getBean(MediaProperties.class);
                    assertThat(properties.endpoint()).isEqualTo("http://minio:9000");
                    assertThat(properties.accessKey()).isEqualTo("local");
                });
    }

    @Test
    void mediaConfigurationRejectsPartialCredentials() {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaStorageConfig.class)
                .withPropertyValues(validMediaProperties())
                .withPropertyValues("phokarta.media.access-key=only-one")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mediaConfigurationRejectsMissingBucket() {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaStorageConfig.class)
                .withPropertyValues(validMediaProperties())
                .withPropertyValues("phokarta.media.bucket=")
                .run(context -> assertThat(context).hasFailed());
    }

    private String[] validMediaProperties() {
        return new String[]{
                "phokarta.media.enabled=true",
                "phokarta.media.bucket=test",
                "phokarta.media.region=us-east-1",
                "phokarta.media.max-bytes=15728640",
                "phokarta.media.max-per-visit=20",
                "phokarta.media.accepted-content-types=image/jpeg,image/png,image/webp",
                "phokarta.media.upload-ttl=15m",
                "phokarta.media.read-ttl=10m",
                "phokarta.media.unattached-ttl=48h",
                "phokarta.media.cleanup-batch-size=100",
                "phokarta.media.cleanup-interval=1h"
        };
    }

    private String[] validProductionProperties() {
        return new String[]{
                "spring.profiles.active=prod",
                "APP_ENVIRONMENT=production-test",
                "spring.datasource.url=jdbc:postgresql://db/phokarta",
                "spring.datasource.username=app",
                "spring.datasource.password=secret"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtBindingConfiguration {
    }
}
