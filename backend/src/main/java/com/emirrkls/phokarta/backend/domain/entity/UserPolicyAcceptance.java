package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_policy_acceptances")
public class UserPolicyAcceptance {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "policy_version", nullable = false, length = 64)
    private String policyVersion;

    @Column(name = "accepted_at", nullable = false)
    private OffsetDateTime acceptedAt;

    protected UserPolicyAcceptance() {
    }

    public UserPolicyAcceptance(UUID id, User user, String policyVersion, OffsetDateTime acceptedAt) {
        this.id = id;
        this.user = user;
        this.policyVersion = policyVersion;
        this.acceptedAt = acceptedAt;
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getPolicyVersion() { return policyVersion; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
}
