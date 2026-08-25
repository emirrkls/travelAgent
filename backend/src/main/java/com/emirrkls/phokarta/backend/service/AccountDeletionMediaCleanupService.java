package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.domain.entity.AccountDeletionMediaJob;
import com.emirrkls.phokarta.backend.observability.ApplicationMetrics;
import com.emirrkls.phokarta.backend.repository.AccountDeletionMediaJobRepository;
import com.emirrkls.phokarta.backend.storage.ObjectStorageService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Deletes object-storage bytes for hard-deleted accounts. Claims are committed
 * before storage is touched. Missing objects are treated as success. Failures
 * stay retryable; the account is never restored.
 */
@Service
public class AccountDeletionMediaCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionMediaCleanupService.class);

    private final AccountDeletionMediaClaims claims;
    private final ObjectStorageService storage;
    private final ApplicationMetrics metrics;

    public AccountDeletionMediaCleanupService(AccountDeletionMediaClaims claims,
                                              AccountDeletionMediaJobRepository jobs,
                                              ObjectStorageService storage,
                                              ApplicationMetrics metrics,
                                              MeterRegistry meterRegistry) {
        this.claims = claims;
        this.storage = storage;
        this.metrics = metrics;
        Gauge.builder("phokarta.account.media_cleanup.backlog", jobs, AccountDeletionMediaJobRepository::count)
                .description("Pending account-deletion object cleanup jobs")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${phokarta.media.cleanup-interval:1h}")
    public void cleanupDue() {
        processDueJobs();
    }

    public void processDueJobs() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (AccountDeletionMediaJob job : claims.claimDue(now)) {
            try {
                storage.delete(job.getStorageKey());
                if (claims.complete(job.getId())) {
                    metrics.accountMediaCleanup("deleted");
                }
            } catch (RuntimeException ex) {
                claims.markFailure(job.getId(), "storage_error");
                metrics.accountMediaCleanup("failed");
                log.info("Account media cleanup retry scheduled deletionId={}", job.getDeletionId());
            }
        }
    }
}
