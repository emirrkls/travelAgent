package com.emirrkls.phokarta.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigurationTest {

    @Test
    void allowsOnlyConfiguredOrigins() {
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(
                new CorsProperties(List.of("https://app.example.com")));
        CorsConfiguration configuration =
                source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/v1/places"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin("https://app.example.com"))
                .isEqualTo("https://app.example.com");
        assertThat(configuration.checkOrigin("https://attacker.example")).isNull();
        assertThat(configuration.getExposedHeaders()).containsExactly("X-Request-Id");
    }

    @Test
    void propertyObjectRejectsWildcardsAndPaths() {
        assertThat(new CorsProperties(List.of("*")).isValidAllowlist()).isFalse();
        assertThat(new CorsProperties(List.of("https://app.example.com/path")).isValidAllowlist())
                .isFalse();
        assertThat(new CorsProperties(List.of("https://app.example.com")).isValidAllowlist())
                .isTrue();
    }
}
