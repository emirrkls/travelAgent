package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.PlaceAggregateV2Response;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.PlaceAggregateV2Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/places")
public class PlaceV2Controller {
    private final PlaceAggregateV2Service service;

    public PlaceV2Controller(PlaceAggregateV2Service service) {
        this.service = service;
    }

    @GetMapping("/{placeId}")
    public PlaceAggregateV2Response get(@PathVariable UUID placeId) {
        return service.get(placeId, SecurityUtils.currentUserId().orElse(null));
    }
}
