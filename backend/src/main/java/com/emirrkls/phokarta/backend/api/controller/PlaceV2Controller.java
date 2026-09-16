package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.PlaceAggregateV2Response;
import com.emirrkls.phokarta.backend.api.dto.CursorPageResponse;
import com.emirrkls.phokarta.backend.api.dto.ExperienceSummaryV2Response;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.PlaceAggregateV2Service;
import com.emirrkls.phokarta.backend.service.ExperienceFeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/places")
public class PlaceV2Controller {
    private final PlaceAggregateV2Service service;
    private final ExperienceFeedService experiences;

    public PlaceV2Controller(PlaceAggregateV2Service service, ExperienceFeedService experiences) {
        this.service = service;
        this.experiences = experiences;
    }

    @GetMapping("/{placeId}")
    public PlaceAggregateV2Response get(@PathVariable UUID placeId) {
        return service.get(placeId, SecurityUtils.currentUserId().orElse(null));
    }

    @GetMapping("/{placeId}/experiences")
    public CursorPageResponse<ExperienceSummaryV2Response> experiences(
            @PathVariable UUID placeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String primary) {
        return experiences.forPlace(placeId, SecurityUtils.currentUserId().orElse(null),
                cursor, size, primary);
    }
}
