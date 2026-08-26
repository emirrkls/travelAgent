package com.emirrkls.phokarta.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "APP_ENVIRONMENT=production-test",
        "PHOKARTA_JWT_SECRET=phokarta-production-test-secret-at-least-32",
        "PHOKARTA_CORS_ALLOWED_ORIGINS=https://app.example.test",
        "PHOKARTA_MEDIA_BUCKET=production-test-media",
        "PHOKARTA_MEDIA_REGION=us-east-1",
        "PHOKARTA_MEDIA_ACCESS_KEY=test-only",
        "PHOKARTA_MEDIA_SECRET_KEY=test-only-secret",
        "MANAGEMENT_SERVER_PORT=8080"
})
class ProductionMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private JdbcTemplate jdbc;

    @Test
    void productionProfileAppliesSchemaWithoutDemoSeed() {
        Integer migrationCount = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        Long userCount = jdbc.queryForObject("select count(*) from users", Long.class);

        assertThat(migrationCount).isEqualTo(9);
        assertThat(userCount).isZero();
        Integer emailColumn = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'users' and column_name = 'email'
                """, Integer.class);
        Integer refreshTable = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'refresh_sessions'
                """, Integer.class);
        assertThat(emailColumn).isEqualTo(1);
        assertThat(refreshTable).isEqualTo(1);
        Integer mutationColumn = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'visits' and column_name = 'client_mutation_id'
                """, Integer.class);
        assertThat(mutationColumn).isEqualTo(1);
        Integer mediaTable = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'media_assets'
                """, Integer.class);
        assertThat(mediaTable).isEqualTo(1);
        Integer deletionJobs = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'account_deletion_media_jobs'
                """, Integer.class);
        assertThat(deletionJobs).isEqualTo(1);
        Integer blocks = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'user_blocks'
                """, Integer.class);
        assertThat(blocks).isEqualTo(1);
        Integer reports = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'reports'
                """, Integer.class);
        assertThat(reports).isEqualTo(1);
        Integer policyAcceptances = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'user_policy_acceptances'
                """, Integer.class);
        assertThat(policyAcceptances).isEqualTo(1);
    }
}
