package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.config.MediaProperties;
import com.emirrkls.phokarta.backend.domain.entity.AccountDeletionMediaJob;
import com.emirrkls.phokarta.backend.repository.AccountDeletionMediaJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Short database transactions on either side of the external object delete for
 * account-deletion cleanup jobs.
 */
@Service
public class AccountDeletionMediaClaims {
    private final AccountDeletionMediaJobRepository jobs;
    private final MediaProperties properties;

    public AccountDeletionMediaClaims(AccountDeletionMediaJobRepository jobs,
                                      MediaProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AccountDeletionMediaJob> claimDue(OffsetDateTime now) {
        List<AccountDeletionMediaJob> claimed = jobs.claimDue(now, properties.cleanupBatchSize());
        OffsetDateTime leaseUntil = now.plus(properties.cleanupInterval());
        claimed.forEach(job -> job.lease(leaseUntil));
        jobs.flush();
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID id) {
        return jobs.deleteJobById(id) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailure(UUID id, String errorCategory) {
        jobs.findById(id).ifPresent(job -> job.markError(errorCategory));
    }
}
