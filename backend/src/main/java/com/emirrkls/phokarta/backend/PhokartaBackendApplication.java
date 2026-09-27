package com.emirrkls.phokarta.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class PhokartaBackendApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(PhokartaBackendApplication.class, args);
        // Only the explicitly enabled place-import profile has this bean. ApplicationRunner
        // prepares the plan; the one-shot must probe after actual readiness, never during startup.
        // No fabricated availability change, timeout extension or background retry is involved.
        try {
            context.getBeansOfType(com.emirrkls.phokarta.backend.config.PlacePilotImportRunner.class)
                    .values().forEach(com.emirrkls.phokarta.backend.config.PlacePilotImportRunner::executeAfterReady);
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }
}
