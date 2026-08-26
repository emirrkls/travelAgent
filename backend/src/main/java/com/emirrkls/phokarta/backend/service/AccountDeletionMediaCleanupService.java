package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.config.MediaProperties;
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

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Deletes object-storage bytes for hard-deleted accounts. Claims are committed
 * before storage is touched. Missing objects are treated as success.
 *
 * <p>Jobs stay durable until {@code created_at + uploadTtl + deletionVerifyGrace}
 * so a still-valid pre-deletion presigned PUT cannot recreate a permanent
 * orphan. Failures stay retryable; the account is never restored.
 */
@Service
public class AccountDeletionMediaCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionMediaCleanupService.class);

    private final AccountDeletionMediaClaims claims;
    private final ObjectStorageService storage;
    private final ApplicationMetrics metrics;
    private final MediaProperties properties;
    private final Clock clock;

    public AccountDeletionMediaCleanupService(AccountDeletionMediaClaims claims,
                                              AccountDeletionMediaJobRepository jobs,
                                              ObjectStorageService storage,
                                              ApplicationMetrics metrics,
                                              MediaProperties properties,
                                              Clock clock,
                                              MeterRegistry meterRegistry) {
        this.claims = claims;
        this.storage = storage;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder("phokarta.account.media_cleanup.backlog", jobs, AccountDeletionMediaJobRepository::count)
                .description("Pending account-deletion object cleanup jobs")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${phokarta.media.cleanup-interval:1h}")
    public void cleanupDue() {
        processDueJobs();
    }

    public void processDueJobs() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (AccountDeletionMediaJob job : claims.claimDue(now)) {
            try {
                storage.delete(job.getStorageKey());
                OffsetDateTime finalAt = finalVerifyAt(job);
                if (!now.isBefore(finalAt)) {
                    if (claims.complete(job.getId())) {
                        metrics.accountMediaCleanup("deleted");
                        log.info("Account media cleanup completed deletionId={}", job.getDeletionId());
                    }
                } else {
                    claims.markAwaitingFinal(job.getId(), finalAt);
                    metrics.accountMediaCleanup("awaiting_final");
                    log.info("Account media cleanup awaiting_final deletionId={}", job.getDeletionId());
                }
            } catch (RuntimeException ex) {
                claims.markFailure(job.getId(), AccountDeletionMediaJob.STORAGE_ERROR);
                metrics.accountMediaCleanup("failed");
                log.info("Account media cleanup retry scheduled deletionId={}", job.getDeletionId());
            }
        }
    }

    /**
     * Instant after which a pre-deletion upload URL is guaranteed expired, plus
     * {@link MediaProperties#deletionVerifyGrace()}. Origin is job {@code created_at}
     * (account-deletion time), covering a PUT issued immediately before deletion.
     */
    OffsetDateTime finalVerifyAt(AccountDeletionMediaJob job) {
        return job.getCreatedAt()
                .plus(properties.uploadTtl())
                .plus(properties.deletionVerifyGrace());
    }
}
