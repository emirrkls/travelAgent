package com.emirrkls.phokarta.backend.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .satisfies(value -> assertThat(UUID.fromString(value)).isNotNull());
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE))
                .isEqualTo(response.getHeader(RequestIdFilter.HEADER));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void acceptsCanonicalUuidAndMakesItAvailableInMdc() throws Exception {
        String supplied = "123e4567-e89b-12d3-a456-426614174000";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(RequestIdFilter.HEADER, supplied);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observedMdc = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> observedMdc.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(supplied);
        assertThat(observedMdc).hasValue(supplied);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesMalformedRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(RequestIdFilter.HEADER, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("not-a-uuid");
        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER))).isNotNull();
    }
}
