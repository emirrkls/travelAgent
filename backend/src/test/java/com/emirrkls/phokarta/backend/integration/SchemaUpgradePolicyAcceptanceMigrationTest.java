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
class SchemaUpgradePolicyAcceptanceMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesV11SchemaToV12PolicyAcceptances() {
        Flyway v11 = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("11")
                .load();
        v11.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword()));

        Integer before = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'user_policy_acceptances'
                """, Integer.class);
        assertThat(before).isZero();

        Flyway upgrade = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load();
        upgrade.migrate();

        Integer after = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name = 'user_policy_acceptances'
                """, Integer.class);
        assertThat(after).isEqualTo(1);
        Integer unique = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'user_policy_acceptances'
                  and indexname = 'idx_user_policy_acceptances_user_version'
                """, Integer.class);
        assertThat(unique).isEqualTo(1);
    }
}
