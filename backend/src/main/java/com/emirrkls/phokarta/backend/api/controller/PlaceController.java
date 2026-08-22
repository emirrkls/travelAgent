package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.NearbyPlaceResponse;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PlaceDetailResponse;
import com.emirrkls.phokarta.backend.api.dto.PlaceSummaryResponse;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.service.PlaceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {
    private final PlaceService service;

    public PlaceController(PlaceService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PlaceSummaryResponse> list(
            @RequestParam(required = false) PlaceCategory category,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("10.0")
            Double minRating,
            @RequestParam(defaultValue = "averageScore,desc") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(category, city, search, minRating, sort, page, size);
    }

    @Operation(summary = "Find places near a coordinate",
            description = "Coordinates are latitude/longitude parameters. PostGIS receives lon/lat. "
                    + "Results are sorted by ascending geodesic distance in meters.")
    @GetMapping("/nearby")
    public List<NearbyPlaceResponse> nearby(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam(defaultValue = "5000") @DecimalMin("1") @DecimalMax("50000")
            double radiusMeters,
            @RequestParam(required = false) PlaceCategory category,
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("10.0")
            Double minRating,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.nearby(lat, lon, radiusMeters, category, minRating, limit);
    }

    @Operation(summary = "Find places in map bounds",
            description = "Uses west,south,east,north in longitude/latitude degrees. "
                    + "Antimeridian-crossing boxes (west >= east) are rejected.")
    @GetMapping("/bounds")
    public List<PlaceSummaryResponse> bounds(
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double west,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double south,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double east,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double north,
            @RequestParam(required = false) PlaceCategory category,
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("10.0")
            Double minRating,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.bounds(west, south, east, north, category, minRating, limit);
    }

    @GetMapping("/{id}")
    public PlaceDetailResponse detail(@PathVariable UUID id) {
        return service.detail(id);
    }
}
