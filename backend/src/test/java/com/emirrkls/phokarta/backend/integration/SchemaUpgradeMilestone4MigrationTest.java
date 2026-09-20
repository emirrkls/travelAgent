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
class SchemaUpgradeMilestone4MigrationTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111415");
    private static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111416");
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000415");
    private static final UUID EXPERIENCE = UUID.fromString("30000000-0000-0000-0000-000000000415");
    private static final UUID COLLECTION = UUID.fromString("40000000-0000-0000-0000-000000000415");
    private static final UUID ACKNOWLEDGEMENT = UUID.fromString("50000000-0000-0000-0000-000000000415");
    private static final UUID CONVERTED_EXPERIENCE = UUID.fromString("30000000-0000-0000-0000-000000000416");

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesV14AndPreservesAcknowledgementAnchorWhenSourceIsDeleted() {
        Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("14")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword()));
        user(jdbc, OWNER, "milestone_owner");
        user(jdbc, AUTHOR, "milestone_author");
        place(jdbc);
        visit(jdbc);
        jdbc.update("""
                insert into collections (
                    id, user_id, title, description, visibility, cover_image, created_at, updated_at
                ) values (?, ?, 'Mixed', '', 'PRIVATE', '', now(), now())
                """, COLLECTION, OWNER);

        Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load()
                .migrate();

        jdbc.update("insert into collection_places values (?, ?, 0, now())", COLLECTION, PLACE);
        jdbc.update("insert into planned_experiences values (?, ?, now())", OWNER, EXPERIENCE);
        jdbc.update("insert into collection_experiences values (?, ?, 1, now())", COLLECTION, EXPERIENCE);
        jdbc.update("""
                insert into experience_acknowledgements (
                    id, user_id, source_experience_id, place_id, primary_experience_code,
                    raw_experience_label, acknowledged_at
                ) values (?, ?, ?, ?, 'GUN_BATIMI', null, now())
                """, ACKNOWLEDGEMENT, OWNER, EXPERIENCE, PLACE);

        assertThat(count(jdbc, "select count(*) from planned_experiences")).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from collection_places where collection_id = ?", COLLECTION))
                .isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from collection_experiences where collection_id = ?", COLLECTION))
                .isEqualTo(1);

        jdbc.update("delete from users where id = ?", AUTHOR);

        assertThat(count(jdbc, "select count(*) from planned_experiences")).isZero();
        assertThat(count(jdbc, "select count(*) from collection_experiences")).isZero();
        assertThat(count(jdbc, "select count(*) from collection_places where collection_id = ?", COLLECTION))
                .isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from experience_acknowledgements where id = ?", ACKNOWLEDGEMENT))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select source_experience_id is null from experience_acknowledgements where id = ?",
                Boolean.class, ACKNOWLEDGEMENT)).isTrue();
        assertThat(jdbc.queryForObject(
                "select primary_experience_code from experience_acknowledgements where id = ?",
                String.class, ACKNOWLEDGEMENT)).isEqualTo("GUN_BATIMI");

        visit(jdbc, CONVERTED_EXPERIENCE, OWNER);
        jdbc.update("update experience_acknowledgements set converted_experience_id = ?, converted_at = now() where id = ?",
                CONVERTED_EXPERIENCE, ACKNOWLEDGEMENT);
        jdbc.update("delete from visits where id = ?", CONVERTED_EXPERIENCE);
        assertThat(jdbc.queryForObject(
                "select converted_experience_id is null and converted_at is not null from experience_acknowledgements where id = ?",
                Boolean.class, ACKNOWLEDGEMENT)).isTrue();

        jdbc.update("delete from users where id = ?", OWNER);
        assertThat(count(jdbc, "select count(*) from experience_acknowledgements")).isZero();
        assertThat(count(jdbc, "select count(*) from collections where id = ?", COLLECTION)).isZero();
    }

    private void user(JdbcTemplate jdbc, UUID id, String username) {
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, created_at, updated_at
                ) values (?, ?, ?, ?, true, 0, 0, 0, 0, '{}', now(), now())
                """, id, username + "@example.test", username, username);
    }

    private void place(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (?, 'Milestone Place', '', 'BEACH', '{}',
                    ST_SetSRID(ST_MakePoint(29.0, 41.0), 4326), 'Istanbul', 'Marmara',
                    'Turkiye', '', '', '{}', 1, now(), now())
                """, PLACE);
    }

    private void visit(JdbcTemplate jdbc) {
        visit(jdbc, EXPERIENCE, AUTHOR);
    }

    private void visit(JdbcTemplate jdbc, UUID experienceId, UUID userId) {
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, 8, 'Story', '', '{}', 'PUBLIC',
                    'UNVERIFIED', now(), now())
                """, experienceId, userId, PLACE);
        jdbc.update("""
                insert into visit_experience_details (
                    visit_id, primary_experience_code, overall_feeling_code, feeling_source,
                    companion_code, time_of_day_code, title, title_source, story, tip,
                    taxonomy_version, created_at, updated_at
                ) values (?, 'GUN_BATIMI', 'GUZELDI', 'EXPLICIT', 'PARTNER', 'EVENING',
                    'Sunset', 'CUSTOM', 'Story', '', 1, now(), now())
                """, experienceId);
    }

    private int count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
