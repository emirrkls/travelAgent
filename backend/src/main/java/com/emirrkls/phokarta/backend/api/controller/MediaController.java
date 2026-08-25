package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.MediaAccessResponse;
import com.emirrkls.phokarta.backend.api.dto.MediaStateResponse;
import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentRequest;
import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentResponse;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MediaController {
    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @Operation(summary = "Create or refresh an image upload intent",
            description = "Idempotent per authenticated owner and clientMediaId. The returned PUT URL "
                    + "is a short-lived bearer credential and must never be logged or persisted.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/media/upload-intents")
    public MediaUploadIntentResponse uploadIntent(
            @Valid @RequestBody MediaUploadIntentRequest request) {
        return media.createUploadIntent(SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Confirm an uploaded image",
            description = "The server verifies object metadata in private storage; client upload "
                    + "success is not trusted.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/media/{mediaId}/confirm")
    public MediaStateResponse confirm(@PathVariable UUID mediaId) {
        return media.confirm(SecurityUtils.requireCurrentUserId(), mediaId);
    }

    @Operation(summary = "Authorize media access",
            description = "Returns a short-lived bearer URL only after Visit visibility authorization. "
                    + "Do not log, persist, cache, or share the URL.")
    @GetMapping("/media/{mediaId}/access")
    public ResponseEntity<MediaAccessResponse> access(@PathVariable UUID mediaId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(media.access(mediaId, SecurityUtils.currentUserId().orElse(null)));
    }
}
