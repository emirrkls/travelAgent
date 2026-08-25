package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisitResponseSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publicAndOwnerResponsesExposeBothLegacyPhotoNames() {
        List<String> legacyUrls = List.of("https://legacy.example.test/photo.jpg");
        PublicVisitResponse publicResponse = new PublicVisitResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Place", UUID.randomUUID(), "owner",
                "Owner", null, LocalDate.of(2026, 8, 25), 8.0, "review",
                legacyUrls, List.of(), VerificationStatus.UNVERIFIED);
        VisitOwnerResponse ownerResponse = new VisitOwnerResponse(
                UUID.randomUUID(), null, LocalDate.of(2026, 8, 25), 8.0, List.of(),
                "review", "memory", legacyUrls, List.of(), Visibility.PRIVATE,
                VerificationStatus.UNVERIFIED);

        assertLegacyPhotoAliases(publicResponse, legacyUrls);
        assertLegacyPhotoAliases(ownerResponse, legacyUrls);
    }

    private void assertLegacyPhotoAliases(Object response, List<String> expected) {
        var json = mapper.valueToTree(response);
        assertThat(json.get("legacyPhotoUrls")).isEqualTo(mapper.valueToTree(expected));
        assertThat(json.get("photos")).isEqualTo(mapper.valueToTree(expected));
    }
}
