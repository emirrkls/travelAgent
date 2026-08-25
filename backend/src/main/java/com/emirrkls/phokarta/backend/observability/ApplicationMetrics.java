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

    private Counter visit(String outcome) {
        return visitCounters.computeIfAbsent(outcome, value -> Counter.builder("phokarta.visit.create")
                .description("Visit creation outcomes")
                .tag("outcome", value)
                .register(registry));
    }
}
