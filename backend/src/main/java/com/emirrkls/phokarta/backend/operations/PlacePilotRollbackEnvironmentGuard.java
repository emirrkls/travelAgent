package com.emirrkls.phokarta.backend.operations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reject unsafe rollback startup before any application bean or migration can execute. */
public class PlacePilotRollbackEnvironmentGuard implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean isolatedSource = application.getAllSources().contains(PlacePilotRollbackApplication.class);
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        boolean rollbackProfile = profiles.contains("place-rollback");
        if (!isolatedSource && !rollbackProfile) return;
        if (!isolatedSource || application.getAllSources().size() != 1
                || !rollbackProfile || !Set.of("place-rollback", "prod").containsAll(profiles)
                || !environment.getProperty("spring.main.sources", "").isBlank()) {
            fail("ISOLATED_PROCESS_REQUIRED");
        }
        String initializer = environment.getProperty("spring.datasource.hikari.connection-init-sql", "");
        if (!initializer.isBlank() && !"SET TIME ZONE 'UTC'".equals(initializer)) {
            fail("UNSAFE_CONFIGURATION");
        }
        if (!environment.getProperty("spring.datasource.hikari.connection-test-query", "").isBlank()
                || !environment.getProperty("spring.datasource.jndi-name", "").isBlank()
                || !environment.getProperty("context.initializer.classes", "").isBlank()
                || !environment.getProperty("context.listener.classes", "").isBlank()
                || !environment.getProperty("logging.config", "").isBlank()
                || !"never".equals(environment.getProperty("spring.sql.init.mode", "never"))
                || Boolean.parseBoolean(environment.getProperty("spring.liquibase.enabled", "false"))) {
            fail("UNSAFE_CONFIGURATION");
        }
        var commandLine = environment.getPropertySources().get("commandLineArgs");
        if (commandLine instanceof org.springframework.core.env.EnumerablePropertySource<?> options) {
            Set<String> permitted = Set.of("spring.profiles.active", "phokarta.place-rollback.enabled",
                    "phokarta.place-rollback.sync-run-id", "phokarta.place-rollback.expected-manifest-hash",
                    "phokarta.place-rollback.dry-run", "phokarta.place-rollback.execute",
                    "phokarta.place-rollback.reason-code");
            for (String option : options.getPropertyNames()) {
                if (!permitted.contains(option)) fail("UNSUPPORTED_OPTION");
            }
        }
        if (!"none".equals(environment.getProperty("spring.main.web-application-type", "none"))
                || Boolean.parseBoolean(environment.getProperty("spring.flyway.enabled", "false"))
                || Boolean.parseBoolean(environment.getProperty("phokarta.place-import.enabled", "false"))
                || Boolean.parseBoolean(environment.getProperty("phokarta.place-canary-gate.enabled", "false"))
                || !"true".equals(environment.getProperty("phokarta.place-rollback.enabled", "false"))) {
            fail("UNSAFE_CONFIGURATION");
        }
        String dryRun = environment.getProperty("phokarta.place-rollback.dry-run", "true");
        String execute = environment.getProperty("phokarta.place-rollback.execute", "false");
        if (!Set.of("true", "false").contains(dryRun) || !Set.of("true", "false").contains(execute)
                || dryRun.equals(execute)) fail("INVALID_MODE");
        try {
            String run = environment.getProperty("phokarta.place-rollback.sync-run-id", "");
            if (!UUID.fromString(run).toString().equals(run)
                    || !environment.getProperty("phokarta.place-rollback.expected-manifest-hash", "")
                    .matches("^[0-9a-f]{64}$")) fail("INVALID_INPUT");
            String reason = environment.getProperty("phokarta.place-rollback.reason-code", "");
            if ("true".equals(execute) || !reason.isBlank()) {
                com.emirrkls.phokarta.backend.service.PlacePilotRollbackOperationsService.Reason.valueOf(
                        reason);
            }
        } catch (IllegalArgumentException invalid) {
            fail("INVALID_INPUT");
        }
        Map<String, Object> safety = new java.util.HashMap<>(Map.of(
                "spring.main.web-application-type", "none", "spring.flyway.enabled", false,
                "spring.sql.init.mode", "never", "spring.liquibase.enabled", false,
                "logging.level.root", "OFF", "debug", false, "trace", false));
        for (var source : environment.getPropertySources()) {
            if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> declared) {
                for (String name : declared.getPropertyNames()) {
                    if (name.startsWith("logging.level.")) safety.put(name, "OFF");
                    if (name.startsWith("LOGGING_LEVEL_")) {
                        safety.put("logging.level." + name.substring(14).toLowerCase(java.util.Locale.ROOT)
                                .replace('_', '.'), "OFF");
                    }
                }
            }
        }
        environment.getPropertySources().addFirst(new MapPropertySource("privateRollbackSafety", safety));
    }

    private static void fail(String category) { throw new IllegalStateException("PLACE_ROLLBACK_FAILED:" + category); }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
