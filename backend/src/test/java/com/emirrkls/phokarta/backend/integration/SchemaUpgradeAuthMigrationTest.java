package com.emirrkls.phokarta.backend.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies auth schema migration upgrades an existing v0.5 (V1-only) database,
 * not only clean installs.
 */
@Testcontainers
class SchemaUpgradeAuthMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesExistingV1SchemaWithLegacyUsers() {
        Flyway v1 = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("1")
                .load();
        v1.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword()));

        jdbc.update("""
                insert into users (
                    id, username, display_name, avatar_url, bio, city_count, country_count,
                    followers_count, following_count, travel_taste, created_at, updated_at
                ) values (
                    '11111111-1111-1111-1111-111111111111', 'legacy_user', 'Legacy',
                    null, null, 0, 0, 0, 0, array[]::text[], now(), now()
                )
                """);

        Flyway upgrade = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load();
        upgrade.migrate();

        String email = jdbc.queryForObject(
                "select email from users where username = 'legacy_user'", String.class);
        assertThat(email).isEqualTo("legacy_user@legacy.phokarta.invalid");
        Integer refreshSessions = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'refresh_sessions'",
                Integer.class);
        Integer authIdentities = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'auth_identities'",
                Integer.class);
        assertThat(refreshSessions).isEqualTo(1);
        assertThat(authIdentities).isEqualTo(1);
    }
}
