package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CollectionV2DetailResponse;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.CollectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/collections")
public class CollectionV2Controller {
    private final CollectionService service;
    public CollectionV2Controller(CollectionService service) { this.service = service; }

    @GetMapping("/{collectionId}")
    public CollectionV2DetailResponse detail(@PathVariable UUID collectionId) {
        return service.detailV2(collectionId, SecurityUtils.currentUserId().orElse(null));
    }
    @PutMapping("/{collectionId}/experiences/{experienceId}")
    public CollectionV2DetailResponse add(@PathVariable UUID collectionId, @PathVariable UUID experienceId) {
        return service.addExperience(collectionId, SecurityUtils.requireCurrentUserId(), experienceId);
    }
    @DeleteMapping("/{collectionId}/experiences/{experienceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID collectionId, @PathVariable UUID experienceId) {
        service.removeExperience(collectionId, SecurityUtils.requireCurrentUserId(), experienceId);
    }
}
