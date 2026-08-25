package com.emirrkls.phokarta.backend.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeleteAccountRequestTest {
    @Test
    void toStringDoesNotIncludePassword() {
        assertThat(new DeleteAccountRequest("SuperSecret1").toString())
                .doesNotContain("SuperSecret1")
                .contains("REDACTED");
    }
}
