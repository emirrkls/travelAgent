package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.ExperienceReadService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/experiences")
public class ExperienceV2Controller {
    private final ExperienceReadService service;

    public ExperienceV2Controller(ExperienceReadService service) {
        this.service = service;
    }

    @Operation(summary = "Read a Visit-backed V2 Experience",
            description = "Read-only compatibility boundary. Reuses Visit visibility and symmetric block rules. "
                    + "Legacy Visits are adapted without mutation. privateMemory is never returned.")
    @GetMapping("/{experienceId}")
    public ExperienceV2Response get(@PathVariable UUID experienceId) {
        return service.getVisible(experienceId, SecurityUtils.currentUserId().orElse(null));
    }
}
