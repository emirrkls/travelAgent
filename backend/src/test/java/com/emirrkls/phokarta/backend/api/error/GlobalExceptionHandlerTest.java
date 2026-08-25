package com.emirrkls.phokarta.backend.api.error;

import com.emirrkls.phokarta.backend.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void unexpectedErrorUsesSafeMessageAndRequestId() {
        String requestId = "123e4567-e89b-12d3-a456-426614174000";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, requestId);

        ResponseEntity<ApiError> response =
                new GlobalExceptionHandler().internal(new RuntimeException("database password leaked"),
                        request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("database password");
        assertThat(response.getBody().requestId()).isEqualTo(requestId);
    }
}
