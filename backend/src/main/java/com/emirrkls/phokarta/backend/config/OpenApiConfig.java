package com.emirrkls.phokarta.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI phokartaOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Short-lived access token from /api/v1/auth/login or register")))
                .info(new Info()
                .title("Phokarta API")
                .version("0.6")
                .contact(new Contact().name("Phokarta"))
                .description("""
                        Geospatial discovery, visits, saved places, and collections with JWT auth.

                        **Authentication (v0.6):** email/password register and login issue a Bearer
                        access token (≈15m) and an opaque refresh token (≈30d, rotated server-side).
                        Owner resources use `/api/v1/me/**` and the authenticated principal — clients
                        must not supply ownership user IDs.

                        **Public:** place discovery/detail, public reviews, PUBLIC collections.
                        **Authenticated:** `/me`, create visit, saved places, collection mutations.

                        Coordinates use WGS84 (SRID 4326). Distances are geodesic meters.
                        """));
    }
}
