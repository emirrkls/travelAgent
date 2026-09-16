package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.domain.model.Visibility;
import org.springframework.stereotype.Component;

/** Global, viewer-independent eligibility for anonymous Community aggregates. */
@Component
public class CommunityContributionPolicy {
    public boolean isEligible(Visibility visibility) {
        return visibility == Visibility.PUBLIC || visibility == Visibility.FRIENDS;
    }
}
