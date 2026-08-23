package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.AuthSessionResponse;
import com.emirrkls.phokarta.backend.api.dto.LoginRequest;
import com.emirrkls.phokarta.backend.api.dto.LogoutRequest;
import com.emirrkls.phokarta.backend.api.dto.RefreshRequest;
import com.emirrkls.phokarta.backend.api.dto.RegisterRequest;
import com.emirrkls.phokarta.backend.api.dto.TokenPairResponse;
import com.emirrkls.phokarta.backend.security.AuthRateLimiter;
import com.emirrkls.phokarta.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {
    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;

    public AuthController(AuthService authService, AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Register with email and password")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthSessionResponse register(@Valid @RequestBody RegisterRequest request,
                                        HttpServletRequest http) {
        rateLimiter.check("register", clientKey(http));
        return authService.register(request);
    }

    @Operation(summary = "Login with email or username and password")
    @PostMapping("/login")
    public AuthSessionResponse login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest http) {
        rateLimiter.check("login", clientKey(http));
        return authService.login(request);
    }

    @Operation(summary = "Rotate refresh token and issue a new access token")
    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest request,
                                     HttpServletRequest http) {
        rateLimiter.check("refresh", clientKey(http));
        return authService.refresh(request);
    }

    @Operation(summary = "Revoke the provided refresh session")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
