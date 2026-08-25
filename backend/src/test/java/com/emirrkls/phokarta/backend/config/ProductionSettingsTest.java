package com.emirrkls.phokarta.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSettingsTest {

    @Test
    void exposesOnlySafeManagementEndpointsAndSeparatesHealthGroups() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "production", new ClassPathResource("application-prod.yml"));

        assertThat(value(sources, "management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
        assertThat(value(sources, "management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(value(sources, "management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
        assertThat(value(sources, "management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
        assertThat(value(sources, "management.server.port"))
                .isEqualTo("${MANAGEMENT_SERVER_PORT:8081}");
        assertThat(value(sources, "logging.structured.format.console")).isEqualTo("ecs");
        assertThat(value(sources, "logging.level.org.hibernate.SQL")).isEqualTo("OFF");
        assertThat(value(sources, "logging.level.org.hibernate.orm.jdbc.bind")).isEqualTo("OFF");
    }

    private Object value(List<PropertySource<?>> sources, String key) {
        return sources.stream()
                .map(source -> source.getProperty(key))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
