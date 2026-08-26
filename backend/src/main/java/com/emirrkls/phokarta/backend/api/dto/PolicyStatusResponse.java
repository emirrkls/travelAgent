package com.emirrkls.phokarta.backend.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyStatusResponse(
        String requiredVersion,
        String acceptedVersion,
        boolean accepted) {
}
