package com.emirrkls.phokarta.backend.support;

import com.emirrkls.phokarta.backend.api.dto.PolicyAcceptanceRequest;
import com.emirrkls.phokarta.backend.service.UgcPolicyService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

public final class PolicyAcceptanceSupport {
    public static final String CURRENT_VERSION = "2026-08-beta";

    private PolicyAcceptanceSupport() {
    }

    /** Use from @Transactional tests so the uncommitted registered user is visible. */
    public static void acceptCurrent(UgcPolicyService ugc, UUID userId) {
        ugc.accept(userId, new PolicyAcceptanceRequest(CURRENT_VERSION));
    }

    public static void acceptCurrent(JdbcTemplate jdbc, UUID userId) {
        accept(jdbc, userId, CURRENT_VERSION);
    }

    public static void accept(JdbcTemplate jdbc, UUID userId, String version) {
        jdbc.update("""
                insert into user_policy_acceptances (id, user_id, policy_version, accepted_at)
                values (?, ?, ?, now())
                on conflict (user_id, policy_version) do nothing
                """, UUID.randomUUID(), userId, version);
    }
}
