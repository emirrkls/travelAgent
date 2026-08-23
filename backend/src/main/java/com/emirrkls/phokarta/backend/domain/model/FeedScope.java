package com.emirrkls.phokarta.backend.domain.model;

import com.emirrkls.phokarta.backend.api.error.ApiException;

import java.util.Locale;

/**
 * Discovery feed / review lens. Friends means mutual follow only.
 */
public enum FeedScope {
    COMMUNITY,
    FRIENDS;

    public static FeedScope fromParam(String value) {
        if (value == null || value.isBlank()) {
            return COMMUNITY;
        }
        try {
            return FeedScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("Unknown scope: " + value);
        }
    }
}
