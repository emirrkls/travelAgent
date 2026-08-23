package com.emirrkls.phokarta.backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated principal derived from a validated JWT access token.
 * Business services must use {@link #userId()} — never client-supplied ownership IDs.
 */
public record AuthenticatedUser(UUID userId) implements Authentication {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) {
        if (!isAuthenticated) {
            throw new IllegalArgumentException("AuthenticatedUser cannot be unauthenticated");
        }
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
