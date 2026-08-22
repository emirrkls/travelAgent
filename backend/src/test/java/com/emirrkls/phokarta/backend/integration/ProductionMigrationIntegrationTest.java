package com.emirrkls.phokarta.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ProductionMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private JdbcTemplate jdbc;

    @Test
    void defaultProfileAppliesSchemaWithoutDemoSeed() {
        Integer migrationCount = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        Long userCount = jdbc.queryForObject("select count(*) from users", Long.class);

        assertThat(migrationCount).isEqualTo(1);
        assertThat(userCount).isZero();
    }
}
