package com.emirrkls.phokarta.backend.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.emirrkls.phokarta.backend.observability.PlaceDetailObservation;
import com.emirrkls.phokarta.backend.observability.PlaceDetailObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
@TestPropertySource(properties = {
        "phokarta.observability.place-detail.enabled=true",
        "phokarta.observability.place-detail.slow-threshold-ms=0"
})
class PlaceDetailObservabilityIntegrationTest {
    private static final String PLACE = "20000000-0000-0000-0000-000000000002";
    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String PRIVATE_MEMORY = "Etiyopya doğal işlenmiş çekirdeği al.";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mockMvc;
    @Autowired private MeterRegistry metrics;
    private Logger logger;
    private ListAppender<ILoggingEvent> capture;

    @BeforeEach
    void captureSanitizedDiagnostics() {
        logger = (Logger) LoggerFactory.getLogger(PlaceDetailObservability.class);
        capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(capture);
        capture.stop();
    }

    @Test
    void actualPostgisDetailKeepsApiAndPrivacyContractWithCorrelatedPhases() throws Exception {
        String body = mockMvc.perform(get("/api/v1/places/{id}", PLACE)
                        .header("X-Request-Id", REQUEST_ID)
                        .header("Authorization", "not-a-bearer-token")
                        .header("Cookie", "test-cookie-must-never-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PLACE))
                .andExpect(jsonPath("$.name").value("Kaktüs Coffee Lab"))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.recentPublicReviews.length()").value(1))
                .andExpect(jsonPath("$.dimensionScores.length()").value(5))
                .andExpect(jsonPath("$.recentPublicReviews[0].privateMemory").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PRIVATE_MEMORY);

        List<ILoggingEvent> diagnostic = capture.list.stream()
                .filter(event -> event.getLevel() == Level.INFO
                        && "slow place detail".equals(event.getFormattedMessage())).toList();
        assertThat(diagnostic).hasSize(1);
        Map<String,Object> record = (Map<String,Object>) diagnostic.getFirst().getKeyValuePairs()
                .stream().filter(kv -> kv.key.equals("place_detail_observation"))
                .findFirst().orElseThrow().value;
        assertThat(record.get("request_id")).isEqualTo(REQUEST_ID);
        assertThat(record.get("canonical_place_id")).isEqualTo(PLACE);
        assertThat(record.get("route_template")).isEqualTo("/api/v1/places/{id}");
        assertThat(record.get("row_counts")).isEqualTo(Map.of(
                "place",1,"rating",1,"dimensions",5,"recent_visits",1));
        var events = (List<PlaceDetailObservation.Event>) record.get("phase_events");
        assertThat(events.stream().map(PlaceDetailObservation.Event::phase).toList())
                .contains(PlaceDetailObservation.Phase.SECURITY_CHAIN_COMPLETE,
                        PlaceDetailObservation.Phase.CONTROLLER_ENTER,
                        PlaceDetailObservation.Phase.SERVICE_ENTER,
                        PlaceDetailObservation.Phase.SERIALIZATION_START,
                        PlaceDetailObservation.Phase.SERIALIZATION_END,
                        PlaceDetailObservation.Phase.REQUEST_FILTER_EXIT);
        assertThat(record.get("serialization_window_observed")).isEqualTo(true);
        assertThat(record.toString()).doesNotContain(PRIVATE_MEMORY, "test-cookie-must-never-log",
                "not-a-bearer-token", "password", "email");
        assertThat(metrics.get("phokarta.place.detail.duration").tag("outcome", "success")
                .timer().count()).isGreaterThanOrEqualTo(1);
    }
}
