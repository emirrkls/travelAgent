package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.SavedPlaceResponse;
import com.emirrkls.phokarta.backend.service.SavedPlaceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/users/{userId}/saved-places")
public class SavedPlaceController {
    private final SavedPlaceService service;

    public SavedPlaceController(SavedPlaceService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<SavedPlaceResponse> list(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(userId, page, size);
    }

    @Operation(summary = "Save a place", description = "Idempotent: repeated calls return the original save.")
    @PostMapping("/{placeId}")
    public SavedPlaceResponse save(@PathVariable UUID userId, @PathVariable UUID placeId) {
        return service.save(userId, placeId);
    }

    @DeleteMapping("/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID userId, @PathVariable UUID placeId) {
        service.remove(userId, placeId);
    }
}
