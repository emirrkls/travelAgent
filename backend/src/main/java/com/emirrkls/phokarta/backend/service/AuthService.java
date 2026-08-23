package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.AuthSessionResponse;
import com.emirrkls.phokarta.backend.api.dto.LoginRequest;
import com.emirrkls.phokarta.backend.api.dto.RefreshRequest;
import com.emirrkls.phokarta.backend.api.dto.RegisterRequest;
import com.emirrkls.phokarta.backend.api.dto.TokenPairResponse;
import com.emirrkls.phokarta.backend.api.dto.UserProfileResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.AuthIdentity;
import com.emirrkls.phokarta.backend.domain.entity.RefreshSession;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.model.AuthProvider;
import com.emirrkls.phokarta.backend.repository.AuthIdentityRepository;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.security.JwtService;
import com.emirrkls.phokarta.backend.security.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final AuthIdentityRepository identities;
    private final UserFollowRepository follows;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;

    public AuthService(UserRepository users, AuthIdentityRepository identities,
                       UserFollowRepository follows, PasswordEncoder passwordEncoder,
                       JwtService jwtService, RefreshTokenService refreshTokens) {
        this.users = users;
        this.identities = identities;
        this.follows = follows;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public AuthSessionResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String username = normalizeUsername(request.username());
        String displayName = request.displayName().trim();
        validatePassword(request.password());

        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS",
                    "An account with this email already exists");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS",
                    "This username is already taken");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        User user = users.save(new User(UUID.randomUUID(), email, username, displayName,
                passwordEncoder.encode(request.password()), now));
        identities.save(new AuthIdentity(UUID.randomUUID(), user, AuthProvider.LOCAL, email, now));
        return sessionFor(user);
    }

    @Transactional
    public AuthSessionResponse login(LoginRequest request) {
        validatePassword(request.password());
        String identifier = request.identifier().trim();
        Optional<User> found = users.findByEmailOrUsernameIgnoreCase(identifier);
        if (found.isEmpty()
                || found.get().getPasswordHash() == null
                || !found.get().isEnabled()
                || !passwordEncoder.matches(request.password(), found.get().getPasswordHash())) {
            throw invalidCredentials();
        }
        return sessionFor(found.get());
    }

    @Transactional
    public TokenPairResponse refresh(RefreshRequest request) {
        String raw = request.refreshToken().trim();
        Optional<RefreshSession> active = refreshTokens.findActiveSession(raw);
        if (active.isPresent()) {
            RefreshSession current = active.get();
            if (!current.getUser().isEnabled()) {
                refreshTokens.revokeFamily(current.getFamilyId());
                throw invalidRefresh();
            }
            RefreshTokenService.IssuedRefreshToken rotated = refreshTokens.rotate(current);
            return tokensFor(current.getUser(), rotated);
        }

        Optional<RefreshSession> any = refreshTokens.findSessionIncludingRevoked(raw);
        if (any.isPresent()) {
            // Possible reuse of a rotated/revoked token — revoke the whole family.
            refreshTokens.revokeFamilyNow(any.get().getFamilyId());
        }
        throw invalidRefresh();
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokens.findSessionIncludingRevoked(refreshToken.trim())
                .ifPresent(refreshTokens::revoke);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse me(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        return toProfile(user);
    }

    private AuthSessionResponse sessionFor(User user) {
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokens.issue(user);
        TokenPairResponse tokens = tokensFor(user, refresh);
        return new AuthSessionResponse(toProfile(user), tokens.accessToken(), tokens.refreshToken(),
                tokens.tokenType(), tokens.expiresIn(), tokens.accessTokenExpiresAt());
    }

    private TokenPairResponse tokensFor(User user, RefreshTokenService.IssuedRefreshToken refresh) {
        String access = jwtService.createAccessToken(user.getId());
        return new TokenPairResponse(access, refresh.rawToken(), "Bearer",
                jwtService.accessTokenExpiresInSeconds(), jwtService.accessTokenExpiresAt());
    }

    public UserProfileResponse toProfile(User user) {
        UUID id = user.getId();
        return new UserProfileResponse(
                id,
                user.getEmail(),
                user.getUsername(),
                user.getDisplayName(),
                user.getBio(),
                user.getAvatarUrl(),
                follows.countFollowers(id),
                follows.countFollowing(id),
                follows.countFriends(id));
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw ApiException.validation("Password must be between 8 and 72 characters");
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Invalid email/username or password");
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Refresh token is invalid or expired");
    }
}
