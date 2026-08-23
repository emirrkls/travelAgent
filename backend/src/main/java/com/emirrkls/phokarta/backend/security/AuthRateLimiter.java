package com.emirrkls.phokarta.backend.security;

import com.emirrkls.phokarta.backend.api.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory rate limiter for auth endpoints (dev/single-node friendly).
 * Production deployments should replace this with Redis-backed limiting.
 */
@Component
public class AuthRateLimiter {
    private static final int MAX_ATTEMPTS = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public void check(String action, String clientKey) {
        String key = action + ":" + clientKey;
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW.toMillis();
        Deque<Long> stamps = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
                stamps.removeFirst();
            }
            if (stamps.size() >= MAX_ATTEMPTS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many attempts. Try again shortly.");
            }
            stamps.addLast(now);
        }
    }
}
