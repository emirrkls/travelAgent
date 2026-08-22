package com.emirrkls.phokarta.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI phokartaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Phokarta API")
                .version("0.4")
                .contact(new Contact().name("Phokarta"))
                .description("""
                        Geospatial discovery, visits, saved places, and collections.

                        **Temporary security warning:** v0.4 has no authentication. Owner-scoped
                        operations require an explicit `userId` solely for demo behavior; clients
                        must not treat this as authorization.

                        Coordinates use WGS84 (SRID 4326). HTTP parameters are named latitude/lat
                        and longitude/lon; PostGIS points are always constructed in longitude,
                        latitude order. Distances are geodesic meters. Bounds use west,south,east,
                        north and antimeridian-crossing boxes are rejected.
                        """));
    }
}
