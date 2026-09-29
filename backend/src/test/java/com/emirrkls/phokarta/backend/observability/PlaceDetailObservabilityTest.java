package com.emirrkls.phokarta.backend.observability;

import com.emirrkls.phokarta.backend.api.dto.PlaceDetailResponse;
import com.emirrkls.phokarta.backend.api.mapper.PlaceMapper;
import com.emirrkls.phokarta.backend.api.mapper.VisitMapper;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.service.PlaceService;
import com.emirrkls.phokarta.backend.web.RequestIdFilter;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaceDetailObservabilityTest {
    private static final String PLACE = "aa000000-0000-4000-8000-000000000001";
    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECRET = "Authorization Cookie JWT password email review-text private-memory DB-credentials";

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/places/" + PLACE);
        request.addHeader(RequestIdFilter.HEADER, REQUEST_ID);
        request.addHeader("Authorization", "Bearer " + SECRET);
        request.addHeader("Cookie", SECRET);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    void disabledDoesNotAllocateOrChangeRequestAndHasNoDetailMetric() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Map<String,Object>> logs = new ArrayList<>();
        PlaceDetailObservability observability = new PlaceDetailObservability(false, 350, registry, null, logs::add);
        RequestIdFilter filter = new RequestIdFilter();
        filter.setPlaceDetailObservability(observability);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(req.getAttribute(PlaceDetailObservation.ATTRIBUTE)).isNull();
            res.getWriter().write("same-contract");
        });
        assertThat(response.getContentAsString()).isEqualTo("same-contract");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(REQUEST_ID);
        assertThat(registry.find("phokarta.place.detail.duration").meters()).isEmpty();
        assertThat(logs).isEmpty();
    }

    @Test
    void enabledFastDetailHasMetricButNoSummaryAndOtherRoutesAreExcluded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Map<String,Object>> logs = new ArrayList<>();
        var observer = new PlaceDetailObservability(true, 100_000, registry, null, logs::add);
        MockHttpServletRequest request = request();
        var trace = observer.begin(request, REQUEST_ID, System.nanoTime());
        assertThat(trace).isNotNull();
        observer.finish(trace, 200, true);
        assertThat(registry.get("phokarta.place.detail.duration").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(logs).isEmpty();
        assertThat(observer.begin(new MockHttpServletRequest("GET", "/api/v1/places/nearby"),
                REQUEST_ID, System.nanoTime())).isNull();
        assertThat(observer.begin(new MockHttpServletRequest("POST", "/api/v1/places/" + PLACE),
                REQUEST_ID, System.nanoTime())).isNull();
    }

    @Test
    void slowSummaryCorrelatesEveryPhaseAndFourRepositoriesWithoutDoubleCounting() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Map<String,Object>> logs = new ArrayList<>();
        var observer = new PlaceDetailObservability(true, 0, registry, null, logs::add);
        MockHttpServletRequest request = request();
        var trace = observer.begin(request, REQUEST_ID, System.nanoTime());
        assertThat(PlaceDetailObservation.current()).isSameAs(trace);
        var mvc = new PlaceDetailMvcObservation();
        assertThat(mvc.boundaryForTest().preHandle(request, new MockHttpServletResponse(), new Object()))
                .isTrue();
        try (var controller = trace.span(PlaceDetailObservation.Operation.CONTROLLER)) {
            try (var service = trace.span(PlaceDetailObservation.Operation.SERVICE)) {
                for (var operation : List.of(PlaceDetailObservation.Operation.PLACE_REPOSITORY,
                        PlaceDetailObservation.Operation.RATING_AGGREGATE,
                        PlaceDetailObservation.Operation.DIMENSION_AGGREGATE,
                        PlaceDetailObservation.Operation.RECENT_VISITS)) {
                    try (var repository = trace.span(operation)) { }
                }
                trace.rows("place", 1);
                trace.rows("rating", 1);
                trace.rows("dimensions", 0);
                trace.rows("recent_visits", 1);
                try (var mapping = trace.span(PlaceDetailObservation.Operation.DTO_MAPPING)) { }
            }
        }
        mvc.beforeBodyWrite(new PlaceDetailResponse(UUID.fromString(PLACE), SECRET, SECRET,
                null, List.of(), 41, 29, "", "", "", "", "", List.of(), 0,
                8.0, 1, List.of(), List.of()), null, null, null, null, null);
        mvc.boundaryForTest().postHandle(request, new MockHttpServletResponse(), new Object(), null);
        observer.finish(trace, 200, true);
        assertThat(logs).hasSize(1);
        Map<String,Object> record = logs.getFirst();
        assertThat(record.get("request_id")).isEqualTo(REQUEST_ID);
        assertThat(record.get("canonical_place_id")).isEqualTo(PLACE);
        assertThat(record.get("row_counts")).isEqualTo(Map.of(
                "place",1,"rating",1,"dimensions",0,"recent_visits",1));
        var events = (List<PlaceDetailObservation.Event>) record.get("phase_events");
        var names = new HashSet<>(events.stream().map(PlaceDetailObservation.Event::phase).toList());
        assertThat(names).contains(PlaceDetailObservation.Phase.REQUEST_FILTER_ENTER,
                PlaceDetailObservation.Phase.SECURITY_CHAIN_COMPLETE,
                PlaceDetailObservation.Phase.CONTROLLER_ENTER,
                PlaceDetailObservation.Phase.SERVICE_ENTER,
                PlaceDetailObservation.Phase.PLACE_REPOSITORY_START,
                PlaceDetailObservation.Phase.PLACE_REPOSITORY_END,
                PlaceDetailObservation.Phase.RATING_AGGREGATE_START,
                PlaceDetailObservation.Phase.RATING_AGGREGATE_END,
                PlaceDetailObservation.Phase.DIMENSION_AGGREGATE_START,
                PlaceDetailObservation.Phase.DIMENSION_AGGREGATE_END,
                PlaceDetailObservation.Phase.RECENT_VISITS_START,
                PlaceDetailObservation.Phase.RECENT_VISITS_END,
                PlaceDetailObservation.Phase.DTO_MAPPING_START,
                PlaceDetailObservation.Phase.DTO_MAPPING_END,
                PlaceDetailObservation.Phase.SERVICE_EXIT,
                PlaceDetailObservation.Phase.CONTROLLER_EXIT,
                PlaceDetailObservation.Phase.SERIALIZATION_START,
                PlaceDetailObservation.Phase.SERIALIZATION_END,
                PlaceDetailObservation.Phase.RESPONSE_COMMIT,
                PlaceDetailObservation.Phase.REQUEST_FILTER_EXIT);
        Map<String,Double> phases = (Map<String,Double>) record.get("phases");
        assertThat(phases.get("leaf_overlap_ms")).isZero();
        assertThat(phases.get("unattributed_ms")).isGreaterThanOrEqualTo(0);
        assertThat(phases.get("measured_nonoverlapping_ms") + phases.get("unattributed_ms"))
                .isCloseTo((Double)record.get("total_ms"), org.assertj.core.data.Offset.offset(0.0001));
        assertThat(phases.get("service_ms")).isGreaterThanOrEqualTo(phases.get("place_repository_ms"));
        assertThat(record.toString()).doesNotContain(SECRET, "Bearer", "Cookie", "Authorization");
        assertThat(registry.get("phokarta.place.detail.duration").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void overlapAccountingUsesUnionAndExposesOverlap() {
        var trace = new PlaceDetailObservation(REQUEST_ID, UUID.fromString(PLACE), System.nanoTime());
        try (var first = trace.span(PlaceDetailObservation.Operation.PLACE_REPOSITORY)) {
            try (var nested = trace.span(PlaceDetailObservation.Operation.RATING_AGGREGATE)) { }
        }
        trace.exit();
        Map<String,Double> phases = (Map<String,Double>) trace.summary(200).get("phases");
        assertThat(phases.get("leaf_overlap_ms")).isGreaterThan(0);
        assertThat(phases.get("unattributed_ms")).isGreaterThanOrEqualTo(0);
        assertThat(phases.get("measured_nonoverlapping_ms") + phases.get("unattributed_ms"))
                .isCloseTo((Double)trace.summary(200).get("total_ms"),
                        org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void repositoryFailurePreservesSameExceptionAndClosesSpans() {
        var repository = mock(PlaceRepository.class);
        RuntimeException failure = new IllegalStateException(SECRET);
        when(repository.findById(UUID.fromString(PLACE))).thenThrow(failure);
        var service = new PlaceService(repository, mock(VisitRepository.class),
                mock(VisitDimensionScoreRepository.class), mock(PlaceMapper.class), mock(VisitMapper.class));
        MockHttpServletRequest request = request();
        var observer = new PlaceDetailObservability(true, 0, new SimpleMeterRegistry(), null, r -> { });
        var trace = observer.begin(request, REQUEST_ID, System.nanoTime());
        assertThatThrownBy(() -> service.detail(UUID.fromString(PLACE)))
                .isSameAs(failure);
        trace.failure(failure);
        trace.exit();
        assertThat(((List<PlaceDetailObservation.Event>)trace.summary(500).get("phase_events"))
                .stream().map(PlaceDetailObservation.Event::phase).toList())
                .contains(PlaceDetailObservation.Phase.PLACE_REPOSITORY_END,
                        PlaceDetailObservation.Phase.SERVICE_EXIT);
        assertThat(trace.summary(500).toString()).doesNotContain(SECRET);
    }

    @Test
    void mappingFailurePreservesSameExceptionAndDoesNotLogPrivateFields() {
        var repository = mock(PlaceRepository.class);
        var visits = mock(VisitRepository.class);
        var dimensions = mock(VisitDimensionScoreRepository.class);
        var mapper = mock(VisitMapper.class);
        var place = mock(Place.class);
        var visit = mock(Visit.class);
        RuntimeException failure = new IllegalArgumentException(SECRET);
        when(repository.findById(UUID.fromString(PLACE))).thenReturn(Optional.of(place));
        when(visits.findRecent(eq(UUID.fromString(PLACE)), any(), any())).thenReturn(List.of(visit));
        when(dimensions.aggregateForPlace(UUID.fromString(PLACE))).thenReturn(List.of());
        when(mapper.toPublic(visit)).thenThrow(failure);
        var service = new PlaceService(repository, visits, dimensions, mock(PlaceMapper.class), mapper);
        MockHttpServletRequest request = request();
        var observer = new PlaceDetailObservability(true, 0, new SimpleMeterRegistry(), null, r -> { });
        var trace = observer.begin(request, REQUEST_ID, System.nanoTime());
        assertThatThrownBy(() -> service.detail(UUID.fromString(PLACE))).isSameAs(failure);
        trace.failure(failure);
        trace.exit();
        assertThat(trace.summary(500).toString()).doesNotContain(SECRET);
    }

    @Test
    void serializationFailureAndDisconnectAreDiagnosticOnlyAndPreserveExceptions() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Map<String,Object>> logs = new ArrayList<>();
        var observer = new PlaceDetailObservability(true, 0, registry, null, logs::add);
        RequestIdFilter filter = new RequestIdFilter();
        filter.setPlaceDetailObservability(observer);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpMessageNotWritableException failure = new HttpMessageNotWritableException(SECRET);
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            PlaceDetailObservation.current().serializationStart();
            throw failure;
        })).isSameAs(failure);
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().get("exception_observed")).isEqualTo(true);
        assertThat(logs.getFirst().get("serialization_window_observed")).isEqualTo(true);
        assertThat(logs.getFirst().toString()).doesNotContain(SECRET);

        MockHttpServletRequest second = request();
        IOException disconnected = new IOException(SECRET);
        assertThatThrownBy(() -> filter.doFilter(second, new MockHttpServletResponse(),
                (req, res) -> { throw disconnected; })).isSameAs(disconnected);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).get("io_failure_observed")).isEqualTo(true);
        assertThat(logs.get(1).get("write_failure_observed")).isEqualTo(false);
        assertThat(logs.get(1).get("response_delivery_confirmed")).isEqualTo(false);
        assertThat(logs.get(1).get("client_abort_exception_type_observed")).isEqualTo(false);
        assertThat(logs.get(1).toString()).doesNotContain(SECRET);

        MockHttpServletRequest third = request();
        var abort = new org.apache.catalina.connector.ClientAbortException(new IOException(SECRET));
        assertThatThrownBy(() -> filter.doFilter(third, new MockHttpServletResponse(),
                (req, res) -> { throw abort; })).isSameAs(abort);
        assertThat(logs).hasSize(3);
        assertThat(logs.get(2).get("client_abort_exception_type_observed")).isEqualTo(true);
        assertThat(logs.get(2).get("write_failure_observed")).isEqualTo(true);
        assertThat(logs.get(2).toString()).doesNotContain(SECRET);
    }

    @Test
    void boundedPoolAndRuntimeSnapshotsContainOnlyNumbers() {
        HikariDataSource source = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(source.getHikariPoolMXBean()).thenReturn(pool);
        when(source.getMaximumPoolSize()).thenReturn(10);
        when(pool.getActiveConnections()).thenReturn(2);
        when(pool.getIdleConnections()).thenReturn(5);
        when(pool.getThreadsAwaitingConnection()).thenReturn(1);
        List<Map<String,Object>> logs = new ArrayList<>();
        var observer = new PlaceDetailObservability(true, 0, new SimpleMeterRegistry(), source, logs::add);
        var trace = observer.begin(request(), REQUEST_ID, System.nanoTime());
        observer.finish(trace, 200, false);
        assertThat(logs.getFirst().get("pool")).isEqualTo(Map.of("active",2,"idle",5,"pending",1,"max",10));
        var runtime = (Map<String,Object>) logs.getFirst().get("jvm");
        assertThat(runtime.keySet()).contains("process_uptime_ms","heap_used_bytes","live_threads",
                "gc_count_cumulative","gc_time_ms_cumulative");
        assertThat(runtime.values()).allMatch(x -> x instanceof Number);
        assertThat(logs.getFirst().toString()).doesNotContain(SECRET);
    }

    @Test
    void thresholdChangesLoggingOnlyNotEndpointMetricOrResult() {
        var fastRegistry = new SimpleMeterRegistry();
        var slowRegistry = new SimpleMeterRegistry();
        List<Map<String,Object>> fastLogs = new ArrayList<>(), slowLogs = new ArrayList<>();
        var fast = new PlaceDetailObservability(true, 100_000, fastRegistry, null, fastLogs::add);
        var slow = new PlaceDetailObservability(true, 0, slowRegistry, null, slowLogs::add);
        var first = fast.begin(request(), REQUEST_ID, System.nanoTime());
        fast.finish(first, 200, true);
        var second = slow.begin(request(), REQUEST_ID, System.nanoTime());
        slow.finish(second, 200, true);
        assertThat(fastLogs).isEmpty();
        assertThat(slowLogs).hasSize(1);
        assertThat(fastRegistry.get("phokarta.place.detail.duration").timer().count()).isEqualTo(1);
        assertThat(slowRegistry.get("phokarta.place.detail.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void fastPathAllocationAndTimingSmokeIsBoundedNotAnSlo() {
        var disabled = new PlaceDetailObservability(false, 350, new SimpleMeterRegistry(), null, r -> { });
        var enabled = new PlaceDetailObservability(true, 100_000, new SimpleMeterRegistry(), null, r -> { });
        MockHttpServletRequest request = request();
        long started = System.nanoTime();
        for (int i=0; i<1000; i++) assertThat(disabled.begin(request, REQUEST_ID, System.nanoTime())).isNull();
        long disabledNs = System.nanoTime() - started;
        started = System.nanoTime();
        for (int i=0; i<1000; i++) {
            var trace = enabled.begin(request, REQUEST_ID, System.nanoTime());
            try (var controller = trace.span(PlaceDetailObservation.Operation.CONTROLLER)) { }
            enabled.finish(trace, 200, false);
        }
        long enabledNs = System.nanoTime() - started;
        System.out.printf("Detail observation local smoke (1000 calls): disabled=%d ms enabled=%d ms; not a latency SLO%n",
                disabledNs/1_000_000, enabledNs/1_000_000);
        assertThat(enabledNs).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
    }
}
