package com.emirrkls.phokarta.backend.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationMetricsTest {

    @Test
    void recordsOnlyBoundedOutcomeAndActionTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApplicationMetrics metrics = new ApplicationMetrics(registry);

        metrics.visitCreateSuccess();
        metrics.visitCreateIdempotencyHit();
        metrics.visitCreateConflict();
        metrics.authRateLimited("login");
        metrics.accountDeletion("success");
        metrics.accountMediaCleanup("deleted");

        assertThat(registry.get("phokarta.visit.create").tag("outcome", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("phokarta.visit.create").tag("outcome", "idempotency_hit")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("phokarta.visit.create").tag("outcome", "conflict")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("phokarta.auth.rate_limited").tag("action", "login")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("phokarta.account.deletion").tag("outcome", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("phokarta.account.media_cleanup").tag("outcome", "deleted")
                .counter().count()).isEqualTo(1);
    }
}
