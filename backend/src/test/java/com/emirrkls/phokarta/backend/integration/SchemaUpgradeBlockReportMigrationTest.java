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
class SchemaUpgradeBlockReportMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesV10SchemaToV11BlocksAndReports() {
        Flyway v10 = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("10")
                .load();
        v10.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword()));

        Integer beforeBlocks = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'user_blocks'
                """, Integer.class);
        Integer beforeReports = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'reports'
                """, Integer.class);
        assertThat(beforeBlocks).isZero();
        assertThat(beforeReports).isZero();

        Flyway upgrade = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load();
        upgrade.migrate();

        Integer afterBlocks = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'user_blocks'
                """, Integer.class);
        Integer afterReports = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'reports'
                """, Integer.class);
        assertThat(afterBlocks).isEqualTo(1);
        assertThat(afterReports).isEqualTo(1);
        Integer deletionJobs = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'account_deletion_media_jobs'
                """, Integer.class);
        assertThat(deletionJobs).isEqualTo(1);
        Integer selfCheck = jdbc.queryForObject("""
                select count(*) from information_schema.check_constraints
                where constraint_name = 'user_blocks_no_self'
                """, Integer.class);
        assertThat(selfCheck).isEqualTo(1);
    }
}
