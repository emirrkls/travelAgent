package com.emirrkls.phokarta.backend.api.dto;

import java.time.Instant;

public record AuthSessionResponse(
        UserProfileResponse user,
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        Instant accessTokenExpiresAt) {
}
