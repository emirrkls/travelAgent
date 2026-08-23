package com.emirrkls.phokarta.backend.security;

import com.emirrkls.phokarta.backend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) {
                try {
                    JwtService.ParsedAccessToken parsed = jwtService.parseAccessToken(token);
                    UUID userId = parsed.userId();
                    if (users.existsById(userId)) {
                        SecurityContextHolder.getContext()
                                .setAuthentication(new AuthenticatedUser(userId));
                    }
                } catch (JwtService.TokenExpiredException | JwtService.InvalidAccessTokenException ex) {
                    SecurityContextHolder.clearContext();
                    request.setAttribute("phokarta.auth.error",
                            ex instanceof JwtService.TokenExpiredException
                                    ? "TOKEN_EXPIRED" : "UNAUTHORIZED");
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
