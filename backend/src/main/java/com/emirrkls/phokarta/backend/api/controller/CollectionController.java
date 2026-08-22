package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CollectionDetailResponse;
import com.emirrkls.phokarta.backend.api.dto.CollectionSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.CreateCollectionRequest;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.service.CollectionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
public class CollectionController {
    private final CollectionService service;

    public CollectionController(CollectionService service) {
        this.service = service;
    }

    @GetMapping("/users/{userId}/collections")
    public PageResponse<CollectionSummaryResponse> list(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(userId, page, size);
    }

    @PostMapping("/users/{userId}/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionDetailResponse create(@PathVariable UUID userId,
                                           @Valid @RequestBody CreateCollectionRequest request) {
        return service.create(userId, request);
    }

    @Operation(summary = "Get a collection",
            description = "No authentication is available in v0.4, so collections are read by ID.")
    @GetMapping("/collections/{collectionId}")
    public CollectionDetailResponse detail(@PathVariable UUID collectionId) {
        return service.detail(collectionId);
    }

    @Operation(description = "userId temporarily enforces ownership until authentication exists.")
    @PostMapping("/collections/{collectionId}/places/{placeId}")
    public CollectionDetailResponse add(@PathVariable UUID collectionId,
                                        @PathVariable UUID placeId,
                                        @RequestParam UUID userId) {
        return service.add(collectionId, userId, placeId);
    }

    @DeleteMapping("/collections/{collectionId}/places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID collectionId, @PathVariable UUID placeId,
                       @RequestParam UUID userId) {
        service.remove(collectionId, userId, placeId);
    }
}
