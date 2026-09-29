package com.emirrkls.phokarta.backend.observability;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Request-scoped, opt-in Detail timing. Never retains the request, response or DTO. */
public final class PlaceDetailObservation {
    public static final String ATTRIBUTE = PlaceDetailObservation.class.getName();

    public enum Phase {
        REQUEST_FILTER_ENTER, SECURITY_CHAIN_COMPLETE, CONTROLLER_ENTER, SERVICE_ENTER,
        PLACE_REPOSITORY_START, PLACE_REPOSITORY_END,
        RATING_AGGREGATE_START, RATING_AGGREGATE_END,
        DIMENSION_AGGREGATE_START, DIMENSION_AGGREGATE_END,
        RECENT_VISITS_START, RECENT_VISITS_END,
        DTO_MAPPING_START, DTO_MAPPING_END, SERVICE_EXIT, CONTROLLER_EXIT,
        SERIALIZATION_START, SERIALIZATION_END, RESPONSE_COMMIT, REQUEST_FILTER_EXIT
    }

    public enum Operation {
        CONTROLLER(Phase.CONTROLLER_ENTER, Phase.CONTROLLER_EXIT),
        SERVICE(Phase.SERVICE_ENTER, Phase.SERVICE_EXIT),
        PLACE_REPOSITORY(Phase.PLACE_REPOSITORY_START, Phase.PLACE_REPOSITORY_END),
        RATING_AGGREGATE(Phase.RATING_AGGREGATE_START, Phase.RATING_AGGREGATE_END),
        DIMENSION_AGGREGATE(Phase.DIMENSION_AGGREGATE_START, Phase.DIMENSION_AGGREGATE_END),
        RECENT_VISITS(Phase.RECENT_VISITS_START, Phase.RECENT_VISITS_END),
        DTO_MAPPING(Phase.DTO_MAPPING_START, Phase.DTO_MAPPING_END);

        private final Phase enter;
        private final Phase exit;

        Operation(Phase enter, Phase exit) {
            this.enter = enter;
            this.exit = exit;
        }
    }

    public record Event(Phase phase, double offsetMs) { }

    private record Interval(long begin, long end) {
        long length() { return Math.max(0, end - begin); }
    }

    public final class Span implements AutoCloseable {
        private final Operation operation;
        private final long begin;
        private boolean closed;

        private Span(Operation operation) {
            this.operation = operation;
            this.begin = stamp(operation.enter);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                long end = stamp(operation.exit);
                intervals.get(operation).add(new Interval(begin, end));
            }
        }
    }

    private final String requestId;
    private final UUID placeId;
    private final long started;
    private final EnumMap<Operation, List<Interval>> intervals = new EnumMap<>(Operation.class);
    private final List<PhaseStamp> events = new ArrayList<>(24);
    private final EnumMap<Phase, Long> firstEvents = new EnumMap<>(Phase.class);
    private final Map<String, Integer> rows = new LinkedHashMap<>();
    private boolean serializationWindowOpen;
    private boolean serializationWindowClosed;
    private boolean exceptionObserved;
    private boolean ioFailureObserved;
    private boolean writeFailureObserved;
    private boolean clientAbortTypeObserved;
    private boolean servletCommitted;

    private record PhaseStamp(Phase phase, long nanos) { }

    PlaceDetailObservation(String requestId, UUID placeId, long started) {
        this.requestId = requestId;
        this.placeId = placeId;
        this.started = started;
        for (Operation operation : Operation.values()) {
            intervals.put(operation, new ArrayList<>(operation == Operation.DTO_MAPPING ? 2 : 1));
        }
        events.add(new PhaseStamp(Phase.REQUEST_FILTER_ENTER, started));
        firstEvents.put(Phase.REQUEST_FILTER_ENTER, started);
    }

    public static PlaceDetailObservation current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof PlaceDetailObservation trace ? trace : null;
    }

    public Span span(Operation operation) {
        return new Span(operation);
    }

    public void rows(String operation, int count) {
        // Only fixed labels from call sites and non-sensitive aggregate counts.
        if (count >= 0 && switch (operation) {
            case "place", "rating", "dimensions", "recent_visits" -> true;
            default -> false;
        }) rows.put(operation, count);
    }

    public void mark(Phase phase) {
        stamp(phase);
    }

    public void serializationStart() {
        if (!serializationWindowOpen) {
            serializationWindowOpen = true;
            stamp(Phase.SERIALIZATION_START);
        }
    }

    public void serializationEnd() {
        if (serializationWindowOpen && !serializationWindowClosed) {
            serializationWindowClosed = true;
            stamp(Phase.SERIALIZATION_END);
        }
    }

    public void failure(Throwable failure) {
        exceptionObserved = true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) ioFailureObserved = true;
            if (cause.getClass().getName().equals("org.apache.catalina.connector.ClientAbortException")) {
                clientAbortTypeObserved = true;
                writeFailureObserved = true;
            }
        }
    }

    public void committed(boolean committed) {
        servletCommitted = committed;
        if (committed) stamp(Phase.RESPONSE_COMMIT);
    }

    public void exit() {
        stamp(Phase.REQUEST_FILTER_EXIT);
    }

    public long durationNanos() {
        Long end = firstEvents.get(Phase.REQUEST_FILTER_EXIT);
        return Math.max(0, (end == null ? System.nanoTime() : end) - started);
    }

    public boolean exceptionObserved() { return exceptionObserved; }

    public Map<String, Object> summary(int status) {
        long total = durationNanos();
        Map<String, Double> timings = new LinkedHashMap<>();
        for (Operation operation : Operation.values()) {
            long nanos = intervals.get(operation).stream().mapToLong(Interval::length).sum();
            timings.put(operation.name().toLowerCase(Locale.ROOT) + "_ms", ms(nanos));
        }
        long serializationNanos = duration(Phase.SERIALIZATION_START, Phase.SERIALIZATION_END);
        timings.put("serialization_window_ms", ms(serializationNanos));
        List<Interval> leaves = new ArrayList<>();
        for (Operation operation : List.of(Operation.PLACE_REPOSITORY, Operation.RATING_AGGREGATE,
                Operation.DIMENSION_AGGREGATE, Operation.RECENT_VISITS, Operation.DTO_MAPPING)) {
            leaves.addAll(intervals.get(operation));
        }
        if (serializationNanos > 0) {
            leaves.add(new Interval(firstEvents.get(Phase.SERIALIZATION_START),
                    firstEvents.get(Phase.SERIALIZATION_END)));
        }
        long inclusiveLeaves = leaves.stream().mapToLong(Interval::length).sum();
        leaves.sort(Comparator.comparingLong(Interval::begin));
        long exclusive = 0, left = -1, right = -1;
        for (Interval leaf : leaves) {
            if (left < 0) {
                left = leaf.begin();
                right = leaf.end();
            } else if (leaf.begin() <= right) {
                right = Math.max(right, leaf.end());
            } else {
                exclusive += Math.max(0, right - left);
                left = leaf.begin();
                right = leaf.end();
            }
        }
        if (left >= 0) exclusive += Math.max(0, right - left);
        // The reported accounting uses only disjoint leaf intervals. Controller and service
        // are inclusive context, never added to these leaves.
        long unattributed = Math.max(0, total - exclusive);
        timings.put("measured_nonoverlapping_ms", ms(exclusive));
        timings.put("leaf_overlap_ms", ms(Math.max(0, inclusiveLeaves - exclusive)));
        timings.put("unattributed_ms", ms(unattributed));
        timings.put("remaining_framework_ms", ms(Math.max(0, total
                - duration(Operation.CONTROLLER) - serializationNanos)));
        List<Event> phaseEvents = events.stream()
                .map(event -> new Event(event.phase(), ms(Math.max(0, event.nanos() - started))))
                .toList();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("request_id", requestId);
        record.put("canonical_place_id", placeId.toString());
        record.put("route_template", "/api/v1/places/{id}");
        record.put("status", status);
        record.put("total_ms", ms(total));
        record.put("phase_model", "hierarchical_inclusive_controller_service; exclusive_disjoint_leaf_accounting");
        record.put("phase_events", phaseEvents);
        record.put("phases", timings);
        record.put("row_counts", Map.copyOf(rows));
        record.put("serialization_window_observed", serializationWindowClosed);
        record.put("response_committed", servletCommitted);
        record.put("response_delivery_confirmed", false);
        record.put("exception_observed", exceptionObserved);
        record.put("io_failure_observed", ioFailureObserved);
        record.put("write_failure_observed", writeFailureObserved);
        record.put("client_abort_exception_type_observed", clientAbortTypeObserved);
        record.put("completed_at", java.time.Instant.now().toString());
        return record;
    }

    private long duration(Operation operation) {
        return intervals.get(operation).stream().mapToLong(Interval::length).sum();
    }

    private long duration(Phase enter, Phase exit) {
        Long start = firstEvents.get(enter);
        Long end = firstEvents.get(exit);
        return start == null || end == null ? 0 : Math.max(0, end - start);
    }

    private long stamp(Phase phase) {
        long now = System.nanoTime();
        events.add(new PhaseStamp(phase, now));
        firstEvents.putIfAbsent(phase, now);
        return now;
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
