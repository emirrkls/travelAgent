package com.emirrkls.phokarta.backend.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaUpgradeAccountDeletionMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesV9SchemaToV10CleanupJobs() {
        Flyway v9 = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("9")
                .load();
        v9.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword()));

        Integer before = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'account_deletion_media_jobs'
                """, Integer.class);
        assertThat(before).isZero();

        Flyway upgrade = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load();
        upgrade.migrate();

        Integer after = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'account_deletion_media_jobs'
                """, Integer.class);
        assertThat(after).isEqualTo(1);
        Integer mediaStillPresent = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'media_assets'
                """, Integer.class);
        assertThat(mediaStillPresent).isEqualTo(1);
    }
}
