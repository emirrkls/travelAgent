package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.domain.model.Visibility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityContributionPolicyTest {
    private final CommunityContributionPolicy policy = new CommunityContributionPolicy();

    @Test
    void publicAndFriendsAreEligibleButPrivateIsNot() {
        assertThat(policy.isEligible(Visibility.PUBLIC)).isTrue();
        assertThat(policy.isEligible(Visibility.FRIENDS)).isTrue();
        assertThat(policy.isEligible(Visibility.PRIVATE)).isFalse();
    }
}
