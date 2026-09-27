package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.emirrkls.phokarta.backend.config.PlacePilotImportRunner;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Actual Spring Boot/Actuator lifecycle, actual HTTP, no datasource or importer at all. */
class PlacePilotBaselineLifecycleTest {
    static final UUID STABLE = UUID.fromString("aa000000-0000-4000-8000-000000000001");
    static final PlacePilotHttpProbeService.ProbeTarget TARGET = new PlacePilotHttpProbeService.ProbeTarget(
            UUID.fromString("e0f36cef-847d-5d96-b8f7-c7a72fef2447"), "Didim baseline query", "BEACH", 37.3751, 27.2678);
    static final AtomicReference<PlacePilotHttpProbeService.ProbeSuite> DURING_RUNNER = new AtomicReference<>();
    static final AtomicReference<PlacePilotHttpProbeService.ProbeSuite> DEFERRED = new AtomicReference<>();

    @Test
    void observesExactBeforeReadyAndAfterReadySurfacesWithoutImport() {
        SpringApplication app = new SpringApplication(ProbeApplication.class);
        app.setDefaultProperties(Map.of(
                "server.port", "0", "management.server.port", "0",
                "management.endpoint.health.probes.enabled", "true",
                "management.endpoint.health.show-details", "never",
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR"));
        try (var context = app.run()) {
            var during = DURING_RUNNER.get();
            var after = new PlacePilotHttpProbeService(new ObjectMapper())
                    .captureBaseline(configuration(context.getEnvironment()), TARGET);
            System.out.println("LOCAL_BEFORE_READY_BASELINE=" + during.toDiagnosticJson());
            System.out.println("LOCAL_AFTER_READY_BASELINE=" + after.toDiagnosticJson());
            assertThat(during.requestCount()).isEqualTo(25);
            assertThat(after.requestCount()).isEqualTo(25);
            assertThat(after.passed()).isTrue();
            // Expected lifecycle defect is tested against real framework behavior, not a mock.
            assertThat(during.surface("health").passed()).isFalse();
            assertThat(during.surface("health").failures()).containsExactly("HTTP_503");
            for (String surface : List.of("search", "map_nearby", "map_bounds", "place_detail")) {
                assertThat(during.surface(surface).passed()).isTrue();
            }
        }
    }

    @Test
    void repairedRealRunnerCallsSameBaselineOnlyAfterActualReadinessAndOnlyOnce() throws Exception {
        DEFERRED.set(null);
        var app = new SpringApplication(ReadyRunnerApplication.class);
        app.setAdditionalProfiles("place-import");
        app.setDefaultProperties(Map.of(
                "server.port", "0", "management.server.port", "0",
                "management.endpoint.health.probes.enabled", "true",
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                "phokarta.place-import.enabled", "true"));
        // Local URLs are resolved from actual ephemeral ports inside the prepared-runner fixture.
        try (var context = app.run()) {
            assertThat(DURING_RUNNER.get().surface("health").failures()).containsExactly("HTTP_503");
            var canary=context.getBean(PlacePilotAutonomousCanaryService.class);
            verifyNoInteractions(canary);
            var runner=context.getBean(PlacePilotImportRunner.class);
            runner.executeAfterReady();
            runner.executeAfterReady();
            verify(canary,times(1)).run(any());
            assertThat(DEFERRED.get().passed()).isTrue();
            assertThat(DEFERRED.get().requestCount()).isEqualTo(25);
        }
    }

    static PlacePilotHttpProbeService.ProbeConfiguration configuration(Environment env) {
        return new PlacePilotHttpProbeService.ProbeConfiguration(
                URI.create("http://127.0.0.1:" + env.getRequiredProperty("local.server.port")),
                URI.create("http://127.0.0.1:" + env.getRequiredProperty("local.management.port") + "/actuator"),
                STABLE, 5, Duration.ofSeconds(5));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class, SecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class})
    @Import(ContractController.class)
    static class ProbeApplication {
        @Bean ApplicationRunner baseline(Environment env) {
            return args -> DURING_RUNNER.set(new PlacePilotHttpProbeService(new ObjectMapper())
                    .captureBaseline(configuration(env), TARGET));
        }
    }

    @Configuration(proxyBeanMethods=false)
    @Import(ProbeApplication.class)
    static class ReadyRunnerApplication {
        @Bean PlacePilotAutonomousCanaryService canary(Environment env) throws Exception {
            var canary=mock(PlacePilotAutonomousCanaryService.class);
            when(canary.run(any())).thenAnswer(call -> {
                var configuration=call.getArgument(0,PlacePilotAutonomousCanaryService.Configuration.class);
                var suite=new PlacePilotHttpProbeService(new ObjectMapper()).captureBaseline(
                        new PlacePilotHttpProbeService.ProbeConfiguration(configuration.baseUrl(),
                                configuration.healthBaseUrl(),STABLE,5,Duration.ofSeconds(5)),TARGET);
                DEFERRED.set(suite);
                assertThat(suite.passed()).isTrue();
                UUID run=UUID.randomUUID();
                return new PlacePilotAutonomousCanaryService.CanaryExecution(
                        new PlacePilotImportService.ImportResult(run,false,"SUCCEEDED",1,1,0,1,0,0,0,0,1),
                        new PlacePilotCanaryGateService.GateResult(UUID.randomUUID(),run,"local-lifecycle","STAGE_1","PASSED",false),
                        JsonNodeFactory.instance.objectNode());
            });
            return canary;
        }
        @Bean PlacePilotImportRunner readyRunner(PlacePilotAutonomousCanaryService canary,
                org.springframework.context.ConfigurableApplicationContext context) {
            return new PlacePilotImportRunner(canary,context) {
                @Override public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
                    var env=context.getEnvironment();
                    var properties=new java.util.HashMap<String,Object>();
                    properties.put("phokarta.place-import.manifest-path","unused-local-fixture.json");
                    properties.put("phokarta.place-import.expected-manifest-hash","a".repeat(64));
                    properties.put("phokarta.place-import.authorization-reference","local-lifecycle-only");
                    properties.put("phokarta.place-import.base-url","http://127.0.0.1:"+env.getRequiredProperty("local.server.port"));
                    properties.put("phokarta.place-import.baseline-place-id",STABLE.toString());
                    ((org.springframework.core.env.ConfigurableEnvironment)env).getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("fixture",properties));
                    super.run(args);
                }
            };
        }
    }

    @RestController
    static class ContractController {
        @GetMapping("/api/v1/places") Map<String,Object> search() { return Map.of("content", List.of()); }
        @GetMapping({"/api/v1/places/nearby", "/api/v1/places/bounds"}) List<Object> map() { return List.of(); }
        @GetMapping("/api/v1/places/aa000000-0000-4000-8000-000000000001") Map<String,Object> detail() {
            return Map.of("id", STABLE, "name", "Staging Harbor Cafe", "category", "CAFE",
                    "latitude", 41.0220, "longitude", 28.9784);
        }
    }
}
