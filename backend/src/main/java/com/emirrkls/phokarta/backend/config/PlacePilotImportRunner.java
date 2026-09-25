package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotImportService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Operational job entry point. It is disabled by default and is never exposed over HTTP. */
@Component
@Profile("place-import")
@ConditionalOnProperty(prefix = "phokarta.place-import", name = "enabled", havingValue = "true")
public class PlacePilotImportRunner implements ApplicationRunner {
    private final PlacePilotImportService importer;
    private final ApplicationContext applicationContext;

    public PlacePilotImportRunner(
            PlacePilotImportService importer,
            ApplicationContext applicationContext
    ) {
        this.importer = importer;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String manifestPath = applicationContext.getEnvironment()
                .getRequiredProperty("phokarta.place-import.manifest-path");
        PlacePilotImportService.ImportResult result = importer.importApproved(Path.of(manifestPath));
        System.out.printf(
                "Place pilot import %s: run=%s created=%d linked=%d review_skipped=%d%n",
                result.alreadyImported() ? "already applied" : result.status(),
                result.runId(), result.createdCount(), result.linkedCount(), result.reviewCount());
    }
}
