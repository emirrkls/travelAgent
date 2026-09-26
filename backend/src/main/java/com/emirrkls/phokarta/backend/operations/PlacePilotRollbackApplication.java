package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.config.PlacePilotRollbackRunner;
import com.emirrkls.phokarta.backend.repository.PlaceGraphProtectionRepository;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackOperationsService;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackService;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JndiDataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Isolated operational process, deliberately not a component-scanned application.
 * There are no HTTP controllers, Flyway, JPA, scheduled workers or startup reconcilers.
 */
@EnableAutoConfiguration(exclude = {
        FlywayAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class, SqlInitializationAutoConfiguration.class,
        LiquibaseAutoConfiguration.class, JndiDataSourceAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
        ServletWebServerFactoryAutoConfiguration.class, WebMvcAutoConfiguration.class,
        ReactiveWebServerFactoryAutoConfiguration.class, WebFluxAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class, ManagementContextAutoConfiguration.class
})
@Import({PlaceGraphProtectionRepository.class, PlacePilotRollbackService.class,
        PlacePilotRollbackOperationsService.class, PlacePilotRollbackRunner.class})
public class PlacePilotRollbackApplication {
    private static final ThreadLocal<PrintStream> OPERATIONAL_OUTPUT = new ThreadLocal<>();
    public static SpringApplication createApplication() {
        SpringApplication application = new SpringApplication(PlacePilotRollbackApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("place-rollback");
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.of(
                "spring.main.web-application-type", "none",
                "spring.flyway.enabled", "false",
                "spring.main.banner-mode", "off",
                "logging.level.root", "OFF"));
        return application;
    }

    public static void main(String[] args) {
        PrintStream operationalOutput = System.out;
        PrintStream operationalErrors = System.err;
        PrintStream suppressedOutput = new PrintStream(OutputStream.nullOutputStream());
        int status = 0;
        try {
            // Suppress even pre-environment framework logging; never replay captured startup logs.
            // The runner explicitly emits its bounded result through the trusted original stream.
            OPERATIONAL_OUTPUT.set(operationalOutput);
            System.setOut(suppressedOutput);
            System.setErr(suppressedOutput);
            createApplication().run(args);
        } catch (Exception ignored) {
            operationalErrors.println("PLACE_ROLLBACK_FAILED:STARTUP_OR_OPERATION_FAILED");
            status = 1;
        } finally {
            System.setOut(operationalOutput);
            System.setErr(operationalErrors);
            OPERATIONAL_OUTPUT.remove();
            suppressedOutput.close();
        }
        if (status != 0) System.exit(status);
    }

    /** The runner is the sole producer of this pre-sanitized, bounded operational result. */
    public static void emitOperationalResult(String result) {
        PrintStream output = OPERATIONAL_OUTPUT.get();
        (output == null ? System.out : output).println(result);
    }
}
