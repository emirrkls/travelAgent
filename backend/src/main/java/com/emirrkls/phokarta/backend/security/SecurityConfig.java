package com.emirrkls.phokarta.backend.security;

import com.emirrkls.phokarta.backend.api.error.ApiError;
import com.emirrkls.phokarta.backend.config.ApplicationProperties;
import com.emirrkls.phokarta.backend.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({
        JwtProperties.class, CorsProperties.class, ApplicationProperties.class
})
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtFilter,
                                            ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) ->
                writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                        attributeOr(request.getAttribute("phokarta.auth.error"), "UNAUTHORIZED"),
                        "Authentication required", request);

        AccessDeniedHandler deniedHandler = (request, response, ex) ->
                writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                        "FORBIDDEN", "Access denied", request);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/places", "/api/v1/places/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activity")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/visits/{visitId}")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/{mediaId}/access")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/collections/{collectionId}")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/{userId}").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/prometheus",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/visits").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/collections/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/collections/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/*/follow").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/*/follow")
                        .authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", RequestIdFilter.HEADER));
        configuration.setExposedHeaders(List.of(RequestIdFilter.HEADER));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static String attributeOr(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper,
                                   int status, String code, String message,
                                   HttpServletRequest request)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                new ApiError(OffsetDateTime.now(ZoneOffset.UTC), status, code, message,
                        request.getRequestURI(), RequestIdFilter.from(request), Map.of()));
    }
}
