package com.emirrkls.phokarta.backend.observability;

import com.emirrkls.phokarta.backend.api.controller.PlaceController;
import com.emirrkls.phokarta.backend.api.dto.PlaceDetailResponse;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.service.PlaceService;
import com.emirrkls.phokarta.backend.web.RequestIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaceDetailMvcObservationTest {
    private static final UUID PLACE = UUID.fromString("aa000000-0000-4000-8000-000000000001");
    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECRET = "do-not-log-this-review-or-private-memory";

    @Test
    void mvcHooksSeeSerializationAndPreserveEntireJsonWhenToggleChanges() throws Exception {
        PlaceService service = mock(PlaceService.class);
        when(service.detail(eq(PLACE), isNull())).thenReturn(new PlaceDetailResponse(
                PLACE, "Staging Harbor Cafe", SECRET, PlaceCategory.CAFE, List.of(),
                41.022, 28.9784, "İstanbul", "İstanbul", "Türkiye", "Test Address",
                "https://example.test/cover", List.of(), 2, 8.0, 1, List.of(), List.of()));
        List<Map<String,Object>> summaries = new ArrayList<>();
        var enabled = new PlaceDetailObservability(true, 0, new SimpleMeterRegistry(), null, summaries::add);
        var disabled = new PlaceDetailObservability(false, 0, new SimpleMeterRegistry(), null, summaries::add);
        String expected = invoke(service, disabled);
        String observed = invoke(service, enabled);
        assertThat(observed).isEqualTo(expected);
        assertThat(summaries).hasSize(1);
        Map<String,Object> record = summaries.getFirst();
        assertThat(record.get("request_id")).isEqualTo(REQUEST_ID);
        assertThat(record.get("serialization_window_observed")).isEqualTo(true);
        assertThat(((List<PlaceDetailObservation.Event>)record.get("phase_events"))
                .stream().map(PlaceDetailObservation.Event::phase).toList())
                .contains(PlaceDetailObservation.Phase.SECURITY_CHAIN_COMPLETE,
                        PlaceDetailObservation.Phase.CONTROLLER_ENTER,
                        PlaceDetailObservation.Phase.CONTROLLER_EXIT,
                        PlaceDetailObservation.Phase.SERIALIZATION_START,
                        PlaceDetailObservation.Phase.SERIALIZATION_END,
                        PlaceDetailObservation.Phase.REQUEST_FILTER_EXIT);
        assertThat(record.toString()).doesNotContain(SECRET);
    }

    private String invoke(PlaceService service, PlaceDetailObservability observer) throws Exception {
        var mvcHooks = new PlaceDetailMvcObservation();
        RequestIdFilter filter = new RequestIdFilter();
        filter.setPlaceDetailObservability(observer);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlaceController(service))
                .setControllerAdvice(mvcHooks)
                .addInterceptors(mvcHooks.boundaryForTest())
                .addFilters(filter)
                .build();
        var response = mvc.perform(get("/api/v1/places/{id}", PLACE)
                        .header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PLACE.toString()))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andReturn().getResponse();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(REQUEST_ID);
        return response.getContentAsString();
    }
}
