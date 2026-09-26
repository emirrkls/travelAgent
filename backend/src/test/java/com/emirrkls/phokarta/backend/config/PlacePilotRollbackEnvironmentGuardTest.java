package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.PhokartaBackendApplication;
import com.emirrkls.phokarta.backend.operations.PlacePilotRollbackApplication;
import com.emirrkls.phokarta.backend.operations.PlacePilotRollbackEnvironmentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlacePilotRollbackEnvironmentGuardTest {
    private final PlacePilotRollbackEnvironmentGuard guard = new PlacePilotRollbackEnvironmentGuard();

    @Test
    void isolatedContextForcesNoWebOrMigrationsAndDisablesSensitiveStartupLogging() {
        MockEnvironment env = environment().withProperty("logging.level.root", "TRACE")
                .withProperty("logging.level.com.zaxxer.hikari", "DEBUG");
        guard.postProcessEnvironment(env, PlacePilotRollbackApplication.createApplication());
        assertThat(env.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(env.getProperty("logging.level.root")).isEqualTo("OFF");
        assertThat(env.getProperty("logging.level.com.zaxxer.hikari")).isEqualTo("OFF");
    }

    @Test
    void normalApplicationCannotStartTheRollbackProfile() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment(),
                new SpringApplication(PhokartaBackendApplication.class)))
                .hasMessage("PLACE_ROLLBACK_FAILED:ISOLATED_PROCESS_REQUIRED");
    }

    @Test
    void refusesUnsafeConfigurationAndOtherOperationalProfilesBeforeBeanStartup() {
        for (String[] unsafe : new String[][]{{"spring.flyway.enabled", "true"},
                {"spring.main.web-application-type", "servlet"},
                {"phokarta.place-import.enabled", "true"},
                {"phokarta.place-canary-gate.enabled", "true"},
                {"spring.datasource.hikari.connection-init-sql", "DELETE FROM places"},
                {"spring.datasource.hikari.connection-test-query", "DELETE FROM places"},
                {"spring.sql.init.mode", "always"}, {"spring.liquibase.enabled", "true"}}) {
            MockEnvironment env = environment().withProperty(unsafe[0], unsafe[1]);
            assertThatThrownBy(() -> guard.postProcessEnvironment(env, PlacePilotRollbackApplication.createApplication()))
                    .hasMessage("PLACE_ROLLBACK_FAILED:UNSAFE_CONFIGURATION");
        }
        MockEnvironment env = environment();
        env.setActiveProfiles("place-rollback", "place-import");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, PlacePilotRollbackApplication.createApplication()))
                .hasMessage("PLACE_ROLLBACK_FAILED:ISOLATED_PROCESS_REQUIRED");
    }

    @Test
    void executionRequiresExplicitReasonAndRejectsUnknownCommandLineOptions() {
        MockEnvironment noReason = environment().withProperty("phokarta.place-rollback.dry-run", "false")
                .withProperty("phokarta.place-rollback.execute", "true");
        assertThatThrownBy(() -> guard.postProcessEnvironment(noReason, PlacePilotRollbackApplication.createApplication()))
                .hasMessage("PLACE_ROLLBACK_FAILED:INVALID_INPUT");
        MockEnvironment unknown = environment();
        unknown.getPropertySources().addFirst(new org.springframework.core.env.SimpleCommandLinePropertySource(
                "--spring.datasource.url=jdbc:postgresql://untrusted/database"));
        assertThatThrownBy(() -> guard.postProcessEnvironment(unknown, PlacePilotRollbackApplication.createApplication()))
                .hasMessage("PLACE_ROLLBACK_FAILED:UNSUPPORTED_OPTION");
    }

    private static MockEnvironment environment() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("phokarta.place-rollback.enabled", "true")
                .withProperty("phokarta.place-rollback.sync-run-id", "d70adea5-6e3f-4c32-92c0-49695eeeb9ce")
                .withProperty("phokarta.place-rollback.expected-manifest-hash", "a".repeat(64));
        env.setActiveProfiles("place-rollback");
        return env;
    }
}
