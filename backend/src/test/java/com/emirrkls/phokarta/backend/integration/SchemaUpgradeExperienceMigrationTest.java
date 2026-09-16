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
class SchemaUpgradeExperienceMigrationTest {
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111199");
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000199");
    private static final UUID LEGACY_VISIT = UUID.fromString("30000000-0000-0000-0000-000000000198");
    private static final UUID NATIVE_VISIT = UUID.fromString("30000000-0000-0000-0000-000000000199");

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesV12WithoutRewritingLegacyAndCascadesNativeSidecar() {
        Flyway v12 = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("12")
                .load();
        v12.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword()));
        insertLegacyFixtures(jdbc);

        Flyway upgrade = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load();
        upgrade.migrate();

        assertThat(count(jdbc, "select count(*) from visits where id = ?", LEGACY_VISIT)).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from visit_experience_details where visit_id = ?", LEGACY_VISIT))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select semantic_state_code from visit_dimension_scores where visit_id = ? and dimension_key = 'SEA'",
                String.class, LEGACY_VISIT)).isNull();

        jdbc.update("""
                insert into visit_experience_details (
                    visit_id, primary_experience_code, overall_feeling_code, feeling_source,
                    companion_code, time_of_day_code, title, title_source, story, tip,
                    taxonomy_version, created_at, updated_at
                ) values (?, 'GUN_BATIMI', 'BAYILDIM', 'EXPLICIT', 'PARTNER', 'EVENING',
                    'Persisted sunset title', 'GENERATED', 'Native story', 'Arrive early',
                    1, now(), now())
                """, NATIVE_VISIT);
        jdbc.update("insert into visit_experience_vibes values (?, 'CALM', 0)", NATIVE_VISIT);
        jdbc.update("insert into visit_experience_practical_signals values (?, 'ARRIVE_EARLY', 0)", NATIVE_VISIT);
        jdbc.update("""
                insert into visit_dimension_scores (
                    visit_id, dimension_key, score, semantic_state_code, template_version
                ) values (?, 'SCENERY', 10, 'VERY_GOOD', 1)
                """, NATIVE_VISIT);

        assertThat(count(jdbc, "select count(*) from visit_experience_details where visit_id = ?", NATIVE_VISIT))
                .isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from visit_experience_vibes where visit_id = ?", NATIVE_VISIT))
                .isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from visit_experience_practical_signals where visit_id = ?", NATIVE_VISIT))
                .isEqualTo(1);

        jdbc.update("delete from visits where id = ?", NATIVE_VISIT);

        assertThat(count(jdbc, "select count(*) from visit_experience_details where visit_id = ?", NATIVE_VISIT))
                .isZero();
        assertThat(count(jdbc, "select count(*) from visit_experience_vibes where visit_id = ?", NATIVE_VISIT))
                .isZero();
        assertThat(count(jdbc, "select count(*) from visit_experience_practical_signals where visit_id = ?", NATIVE_VISIT))
                .isZero();
        assertThat(count(jdbc, "select count(*) from visit_dimension_scores where visit_id = ?", NATIVE_VISIT))
                .isZero();
        assertThat(count(jdbc, "select count(*) from visits where id = ?", LEGACY_VISIT)).isEqualTo(1);
    }

    private void insertLegacyFixtures(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, created_at, updated_at
                ) values (?, 'migration@example.test', 'migration-user', 'Migration User', true,
                    0, 0, 0, 0, '{}', now(), now())
                """, USER);
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (?, 'Migration Place', '', 'BEACH', '{}',
                    ST_SetSRID(ST_MakePoint(26.75, 38.67), 4326), 'İzmir', 'Aegean',
                    'Türkiye', '', '', '{}', 1, now(), now())
                """, PLACE);
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, 8.7, 'Legacy story', 'Private memory',
                    '{}', 'PUBLIC', 'UNVERIFIED', now(), now())
                """, LEGACY_VISIT, USER, PLACE);
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, 10, '', '', '{}', 'PRIVATE',
                    'UNVERIFIED', now(), now())
                """, NATIVE_VISIT, USER, PLACE);
        jdbc.update("insert into visit_dimension_scores values (?, 'SEA', 8.7)", LEGACY_VISIT);
    }

    private int count(JdbcTemplate jdbc, String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }
}
