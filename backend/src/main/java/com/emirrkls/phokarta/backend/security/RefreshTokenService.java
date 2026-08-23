package com.emirrkls.phokarta.backend.security;

import com.emirrkls.phokarta.backend.domain.entity.RefreshSession;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.repository.RefreshSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private final RefreshSessionRepository sessions;
    private final JwtProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshSessionRepository sessions, JwtProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @Transactional
    public IssuedRefreshToken issue(User user) {
        return issue(user, UUID.randomUUID());
    }

    @Transactional
    public IssuedRefreshToken rotate(RefreshSession current) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IssuedRefreshToken next = issue(current.getUser(), current.getFamilyId());
        current.revoke(now, next.sessionId());
        sessions.save(current);
        return next;
    }

    @Transactional
    public Optional<RefreshSession> findActiveSession(String rawToken) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return sessions.findByTokenHashWithUser(hash(rawToken))
                .filter(session -> session.isActive(now));
    }

    @Transactional
    public Optional<RefreshSession> findSessionIncludingRevoked(String rawToken) {
        return sessions.findByTokenHashWithUser(hash(rawToken));
    }

    @Transactional
    public void revoke(RefreshSession session) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (session.getRevokedAt() == null) {
            session.revoke(now);
            sessions.save(session);
        }
    }

    @Transactional
    public void revokeFamily(UUID familyId) {
        sessions.revokeFamily(familyId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Commits family revocation even when the caller subsequently throws
     * (e.g. refresh-token reuse detection).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void revokeFamilyNow(UUID familyId) {
        sessions.revokeFamily(familyId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private IssuedRefreshToken issue(User user, UUID familyId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plus(properties.refreshTokenTtl());
        UUID sessionId = UUID.randomUUID();
        String rawToken = generateRawToken();
        RefreshSession session = new RefreshSession(sessionId, user, familyId, hash(rawToken),
                now, expiresAt);
        sessions.save(session);
        return new IssuedRefreshToken(sessionId, rawToken, expiresAt);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record IssuedRefreshToken(UUID sessionId, String rawToken, OffsetDateTime expiresAt) {
    }
}
