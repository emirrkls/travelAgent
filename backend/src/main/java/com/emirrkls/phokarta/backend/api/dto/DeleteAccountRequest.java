package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.Size;

public record DeleteAccountRequest(
        @Size(min = 1, max = 72) String currentPassword) {

    @Override
    public String toString() {
        return "DeleteAccountRequest[currentPassword=REDACTED]";
    }
}
