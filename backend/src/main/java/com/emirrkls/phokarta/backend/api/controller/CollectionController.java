package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CollectionDetailResponse;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.CollectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @Operation(summary = "Get a collection",
            description = """
                    PUBLIC collections are readable without authentication.
                    PRIVATE collections require the owner.
                    FRIENDS collections are readable by the owner or a mutual-follow friend.
                    """)
    @GetMapping("/collections/{collectionId}")
    public CollectionDetailResponse detail(@PathVariable UUID collectionId) {
        return service.detail(collectionId, SecurityUtils.currentUserId().orElse(null));
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/collections/{collectionId}/places/{placeId}")
    public CollectionDetailResponse add(@PathVariable UUID collectionId,
                                        @PathVariable UUID placeId) {
        return service.add(collectionId, SecurityUtils.requireCurrentUserId(), placeId);
    }

    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/collections/{collectionId}/places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID collectionId, @PathVariable UUID placeId) {
        service.remove(collectionId, SecurityUtils.requireCurrentUserId(), placeId);
    }
}
