package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CreateExperienceV2Request;
import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.api.dto.ExperienceSummaryV2Response;
import com.emirrkls.phokarta.backend.api.dto.CursorPageResponse;
import com.emirrkls.phokarta.backend.domain.model.ExperienceFeedLens;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.ExperienceReadService;
import com.emirrkls.phokarta.backend.service.ExperienceWriteService;
import com.emirrkls.phokarta.backend.service.ExperienceFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/experiences")
public class ExperienceV2Controller {
    private final ExperienceReadService reads;
    private final ExperienceWriteService writes;
    private final ExperienceFeedService feeds;

    public ExperienceV2Controller(ExperienceReadService reads, ExperienceWriteService writes,
                                  ExperienceFeedService feeds) {
        this.reads = reads;
        this.writes = writes;
        this.feeds = feeds;
    }

    @Operation(summary = "Publish a native Visit-backed V2 Experience")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExperienceV2Response create(@Valid @RequestBody CreateExperienceV2Request request) {
        return writes.create(SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Read a Visit-backed V2 Experience",
            description = "Read-only compatibility boundary. Reuses Visit visibility and symmetric block rules. "
                    + "Legacy Visits are adapted without mutation. privateMemory is never returned.")
    @GetMapping("/{experienceId}")
    public ExperienceV2Response get(@PathVariable UUID experienceId) {
        return reads.getVisible(experienceId, SecurityUtils.currentUserId().orElse(null));
    }

    @Operation(summary = "Browse viewer-authorized V2 Experience summaries")
    @GetMapping("/feed")
    public CursorPageResponse<ExperienceSummaryV2Response> feed(
            @RequestParam(defaultValue = "FOR_YOU") ExperienceFeedLens lens,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, name = "q") String search,
            @RequestParam(required = false) String primary,
            @RequestParam(required = false) String vibe,
            @RequestParam(required = false, name = "lat") Double latitude,
            @RequestParam(required = false, name = "lon") Double longitude,
            @RequestParam(required = false, defaultValue = "10000") Double radiusMeters) {
        return feeds.explore(lens, SecurityUtils.currentUserId().orElse(null), cursor, size,
                search, primary, vibe, latitude, longitude, radiusMeters);
    }
}
