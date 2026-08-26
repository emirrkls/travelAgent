package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.PolicyAcceptanceRequest;
import com.emirrkls.phokarta.backend.api.dto.PolicyStatusResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.config.PolicyProperties;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.repository.UserPolicyAcceptanceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@EnableConfigurationProperties(PolicyProperties.class)
public class UgcPolicyService {
    public static final String POLICY_ACCEPTANCE_REQUIRED = "POLICY_ACCEPTANCE_REQUIRED";

    private final PolicyProperties properties;
    private final UserRepository users;
    private final UserPolicyAcceptanceRepository acceptances;

    public UgcPolicyService(PolicyProperties properties, UserRepository users,
                            UserPolicyAcceptanceRepository acceptances) {
        this.properties = properties;
        this.users = users;
        this.acceptances = acceptances;
    }

    public String requiredVersion() {
        return properties.requiredVersion();
    }

    @Transactional(readOnly = true)
    public PolicyStatusResponse status(UUID userId) {
        if (!users.existsById(userId)) {
            throw ApiException.notFound("User", userId);
        }
        return statusFor(userId);
    }

    @Transactional
    public PolicyStatusResponse accept(UUID userId, PolicyAcceptanceRequest request) {
        String required = requiredVersion();
        String requested = request.policyVersion() == null ? "" : request.policyVersion().trim();
        if (!required.equals(requested)) {
            throw ApiException.validation("Only the current required policy version can be accepted");
        }
        users.lockAccount(userId);
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        if (!acceptances.existsByUser_IdAndPolicyVersion(userId, required)) {
            acceptances.insertIfAbsent(UUID.randomUUID(), user.getId(), required,
                    OffsetDateTime.now(ZoneOffset.UTC));
        }
        return statusFor(userId);
    }

    public void requireAccepted(UUID userId) {
        if (acceptances.existsByUser_IdAndPolicyVersion(userId, requiredVersion())) {
            return;
        }
        throw ApiException.policyAcceptanceRequired(requiredVersion());
    }

    private PolicyStatusResponse statusFor(UUID userId) {
        String required = requiredVersion();
        String acceptedVersion = acceptances.findTopByUser_IdOrderByAcceptedAtDesc(userId)
                .map(row -> row.getPolicyVersion())
                .orElse(null);
        boolean accepted = acceptances.existsByUser_IdAndPolicyVersion(userId, required);
        return new PolicyStatusResponse(required, acceptedVersion, accepted);
    }
}
