package com.emirrkls.phokarta.backend.api.dto;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void mapsHasNextFromSpringPage() {
        var source = new PageImpl<>(List.of("one", "two"), PageRequest.of(0, 2), 3);

        PageResponse<Integer> response = PageResponse.from(source, String::length);

        assertThat(response.content()).containsExactly(3, 3);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.totalElements()).isEqualTo(3);
    }
}
