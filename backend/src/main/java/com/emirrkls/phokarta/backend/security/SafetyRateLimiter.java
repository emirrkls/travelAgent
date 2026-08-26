package com.emirrkls.phokarta.backend.security;

import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.config.SafetyProperties;
import com.emirrkls.phokarta.backend.observability.ApplicationMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter for block and report actions (single-node).
 */
@Component
@EnableConfigurationProperties(SafetyProperties.class)
public class SafetyRateLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final SafetyProperties properties;
    private final ApplicationMetrics metrics;

    public SafetyRateLimiter(SafetyProperties properties, ApplicationMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    public void checkReport(UUID userId) {
        check("report", userId.toString(), properties.reportMaxPerHour(), "REPORT_RATE_LIMITED");
    }

    public void checkBlock(UUID userId) {
        check("block", userId.toString(), properties.blockMaxPerHour(), "RATE_LIMITED");
    }

    private void check(String action, String clientKey, int maxAttempts, String errorCode) {
        String key = action + ":" + clientKey;
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW.toMillis();
        Deque<Long> stamps = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
                stamps.removeFirst();
            }
            if (stamps.size() >= maxAttempts) {
                metrics.safetyRateLimited(action);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, errorCode,
                        "Too many attempts. Try again shortly.");
            }
            stamps.addLast(now);
        }
    }
}
