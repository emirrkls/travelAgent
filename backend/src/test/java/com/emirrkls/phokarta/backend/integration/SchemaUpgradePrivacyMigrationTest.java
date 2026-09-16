package com.emirrkls.phokarta.backend.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaUpgradePrivacyMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesV13WithPublicDefaultAndParticipantCascades() {
        Flyway v13 = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema").target("13").load();
        v13.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword()));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        insertUser(jdbc, a, "a@example.test", "privacy_a");
        insertUser(jdbc, b, "b@example.test", "privacy_b");

        Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema").load().migrate();

        assertThat(jdbc.queryForObject(
                "select profile_visibility from users where id = ?", String.class, a))
                .isEqualTo("PUBLIC");
        UUID request = UUID.randomUUID();
        jdbc.update("""
                insert into follow_requests (
                    id, requester_user_id, target_user_id, status, created_at
                ) values (?, ?, ?, 'PENDING', now())
                """, request, a, b);
        jdbc.update("delete from users where id = ?", b);
        assertThat(jdbc.queryForObject(
                "select count(*) from follow_requests where id = ?", Integer.class, request))
                .isZero();
    }

    private void insertUser(JdbcTemplate jdbc, UUID id, String email, String username) {
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, created_at, updated_at
                ) values (?, ?, ?, ?, true, 0, 0, 0, 0, '{}', now(), now())
                """, id, email, username, username);
    }
}
