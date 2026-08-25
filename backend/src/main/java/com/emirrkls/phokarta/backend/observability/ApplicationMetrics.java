package com.emirrkls.phokarta.backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApplicationMetrics {
    private final MeterRegistry registry;
    private final Map<String, Counter> visitCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> authRateLimitCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> mediaUploadCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> mediaConfirmCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> mediaCleanupCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> accountDeletionCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> accountMediaCleanupCounters = new ConcurrentHashMap<>();

    public ApplicationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void visitCreateSuccess() {
        visit("success").increment();
    }

    public void visitCreateIdempotencyHit() {
        visit("idempotency_hit").increment();
    }

    public void visitCreateConflict() {
        visit("conflict").increment();
    }

    public void authRateLimited(String action) {
        authRateLimitCounters.computeIfAbsent(action, value -> Counter.builder("phokarta.auth.rate_limited")
                        .description("Rejected authentication requests")
                        .tag("action", value)
                        .register(registry))
                .increment();
    }

    public void mediaUploadIntent(String outcome) {
        mediaCounter(mediaUploadCounters, "phokarta.media.upload_intent",
                "Media upload intent outcomes", outcome).increment();
    }

    public void mediaConfirm(String outcome) {
        mediaCounter(mediaConfirmCounters, "phokarta.media.confirm",
                "Media confirmation outcomes", outcome).increment();
    }

    public void mediaCleanup(String outcome) {
        mediaCounter(mediaCleanupCounters, "phokarta.media.cleanup",
                "Media orphan cleanup outcomes", outcome).increment();
    }

    public void accountDeletion(String outcome) {
        mediaCounter(accountDeletionCounters, "phokarta.account.deletion",
                "Account deletion request outcomes", outcome).increment();
    }

    public void accountMediaCleanup(String outcome) {
        mediaCounter(accountMediaCleanupCounters, "phokarta.account.media_cleanup",
                "Account-deletion object cleanup outcomes", outcome).increment();
    }

    private Counter visit(String outcome) {
        return visitCounters.computeIfAbsent(outcome, value -> Counter.builder("phokarta.visit.create")
                .description("Visit creation outcomes")
                .tag("outcome", value)
                .register(registry));
    }

    private Counter mediaCounter(Map<String, Counter> counters, String name,
                                 String description, String outcome) {
        return counters.computeIfAbsent(outcome, value -> Counter.builder(name)
                .description(description)
                .tag("outcome", value)
                .register(registry));
    }
}
