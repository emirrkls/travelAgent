package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CapabilitiesV2Response;
import com.emirrkls.phokarta.backend.config.ProfilePrivacyProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/capabilities")
public class CapabilitiesV2Controller {
    private final ProfilePrivacyProperties properties;

    public CapabilitiesV2Controller(ProfilePrivacyProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public CapabilitiesV2Response get() {
        return new CapabilitiesV2Response(properties.enabled());
    }
}
