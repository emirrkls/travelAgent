package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.DeleteAccountRequest;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.observability.ApplicationMetrics;
import com.emirrkls.phokarta.backend.repository.AccountDeletionMediaJobRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitMediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Hard-deletes the authenticated account and all user-owned product data in one
 * database transaction. Object-storage bytes are deleted asynchronously from
 * durable {@code account_deletion_media_jobs} rows captured before the user row
 * disappears. No object-storage I/O runs inside the transaction.
 */
@Service
public class AccountDeletionService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository users;
    private final VisitMediaRepository visitMedia;
    private final AccountDeletionMediaJobRepository mediaJobs;
    private final PasswordEncoder passwordEncoder;
    private final AccountDeletionMediaCleanupService mediaCleanup;
    private final ApplicationMetrics metrics;

    public AccountDeletionService(UserRepository users, VisitMediaRepository visitMedia,
                                  AccountDeletionMediaJobRepository mediaJobs,
                                  PasswordEncoder passwordEncoder,
                                  AccountDeletionMediaCleanupService mediaCleanup,
                                  ApplicationMetrics metrics) {
        this.users = users;
        this.visitMedia = visitMedia;
        this.mediaJobs = mediaJobs;
        this.passwordEncoder = passwordEncoder;
        this.mediaCleanup = mediaCleanup;
        this.metrics = metrics;
    }

    @Transactional
    public void deleteCurrentAccount(UUID userId, DeleteAccountRequest request) {
        users.lockAccount(userId);
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            metrics.accountDeletion("already_gone");
            throw ApiException.unauthorized("UNAUTHORIZED", "Authentication required");
        }
        verifyReauthentication(user, request);

        UUID deletionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        mediaJobs.enqueueOwnedMedia(userId, deletionId, now);
        visitMedia.deleteForUser(userId);
        int deleted = users.deleteUserById(userId);
        if (deleted != 1) {
            metrics.accountDeletion("failure");
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "Account could not be deleted");
        }

        metrics.accountDeletion("success");
        log.info("Account deletion committed deletionId={}", deletionId);
        scheduleCleanupAfterCommit();
    }

    private void verifyReauthentication(User user, DeleteAccountRequest request) {
        if (user.getPasswordHash() == null) {
            return;
        }
        String presented = request == null ? null : request.currentPassword();
        if (presented == null || presented.isBlank()) {
            metrics.accountDeletion("invalid_password");
            throw ApiException.validation("Current password is required");
        }
        if (presented.length() < 8 || presented.length() > 72
                || !passwordEncoder.matches(presented, user.getPasswordHash())) {
            metrics.accountDeletion("invalid_password");
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD",
                    "Current password is incorrect");
        }
    }

    private void scheduleCleanupAfterCommit() {
        Runnable run = () -> {
            try {
                mediaCleanup.processDueJobs();
            } catch (RuntimeException ex) {
                log.info("Account media cleanup deferred after deletion commit: {}", ex.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run.run();
                }
            });
        } else {
            run.run();
        }
    }
}
