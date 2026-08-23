package com.emirrkls.phokarta.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    public static final String TOKEN_TYPE_ACCESS = "access";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "phokarta.jwt.secret must be at least 32 bytes (set PHOKARTA_JWT_SECRET)");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String createAccessToken(UUID userId) {
        Instant now = Instant.now();
        Instant expires = now.plus(properties.accessTokenTtl());
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .claim("typ", TOKEN_TYPE_ACCESS)
                .signWith(key)
                .compact();
    }

    public Instant accessTokenExpiresAt() {
        return Instant.now().plus(properties.accessTokenTtl());
    }

    public long accessTokenExpiresInSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    public ParsedAccessToken parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String typ = claims.get("typ", String.class);
            if (typ != null && !TOKEN_TYPE_ACCESS.equals(typ)) {
                throw new JwtException("Unexpected token type");
            }
            UUID userId = UUID.fromString(claims.getSubject());
            return new ParsedAccessToken(userId, claims.getExpiration().toInstant());
        } catch (ExpiredJwtException ex) {
            throw new TokenExpiredException();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidAccessTokenException();
        }
    }

    public record ParsedAccessToken(UUID userId, Instant expiresAt) {
    }

    public static final class TokenExpiredException extends RuntimeException {
    }

    public static final class InvalidAccessTokenException extends RuntimeException {
    }
}
