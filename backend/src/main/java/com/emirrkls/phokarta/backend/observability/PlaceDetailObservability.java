package com.emirrkls.phokarta.backend.observability;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PlaceDetailObservability {
    private static final Logger log = LoggerFactory.getLogger(PlaceDetailObservability.class);
    private static final Pattern DETAIL = Pattern.compile(
            "^/api/v1/places/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    private final boolean enabled;
    private final long slowThresholdNanos;
    private final MeterRegistry registry;
    private final DataSource dataSource;
    private final Consumer<Map<String, Object>> slowSink;

    @Autowired
    public PlaceDetailObservability(
            @Value("${phokarta.observability.place-detail.enabled:false}") boolean enabled,
            @Value("${phokarta.observability.place-detail.slow-threshold-ms:350}") long thresholdMs,
            MeterRegistry registry, ObjectProvider<DataSource> dataSource) {
        this(enabled, thresholdMs, registry, dataSource.getIfAvailable(),
                record -> log.atInfo().addKeyValue("place_detail_observation", record)
                        .log("slow place detail"));
    }

    // Package-private seam for deterministic tests; production uses the structured logger.
    PlaceDetailObservability(boolean enabled, long thresholdMs, MeterRegistry registry,
                             DataSource dataSource, Consumer<Map<String, Object>> slowSink) {
        if (thresholdMs < 0) throw new IllegalArgumentException("slow-threshold-ms must be nonnegative");
        this.enabled = enabled;
        this.slowThresholdNanos = TimeUnit.MILLISECONDS.toNanos(thresholdMs);
        this.registry = registry;
        this.dataSource = dataSource;
        this.slowSink = slowSink;
    }

    public boolean enabled() { return enabled; }

    public PlaceDetailObservation begin(HttpServletRequest request, String requestId, long started) {
        if (!enabled || !"GET".equals(request.getMethod())) return null;
        Matcher match = DETAIL.matcher(request.getRequestURI());
        if (!match.matches()) return null;
        UUID placeId = UUID.fromString(match.group(1));
        PlaceDetailObservation observation = new PlaceDetailObservation(requestId, placeId, started);
        request.setAttribute(PlaceDetailObservation.ATTRIBUTE, observation);
        return observation;
    }

    public void finish(PlaceDetailObservation observation, int status, boolean committed) {
        observation.committed(committed);
        observation.exit();
        long total = observation.durationNanos();
        Timer.builder("phokarta.place.detail.duration")
                .description("Observed synchronous Place Detail filter-chain duration")
                .tag("outcome", observation.exceptionObserved() ? "exception"
                        : status >= 500 ? "server_error" : status >= 400 ? "client_error" : "success")
                .register(registry).record(total, TimeUnit.NANOSECONDS);
        if (total >= slowThresholdNanos) {
            Map<String, Object> record = new LinkedHashMap<>(observation.summary(status));
            record.put("jvm", runtimeSnapshot());
            record.put("pool", poolSnapshot());
            record.put("diagnostic_threshold_ms", TimeUnit.NANOSECONDS.toMillis(slowThresholdNanos));
            // Never inspect or log HttpServletRequest, response, exception messages, entities,
            // SQL statements, binds, environment or credentials.
            slowSink.accept(Map.copyOf(record));
        }
    }

    private Map<String, Object> runtimeSnapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("process_uptime_ms", Math.max(0, ManagementFactory.getRuntimeMXBean().getUptime()));
        values.put("heap_used_bytes", Math.max(0, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
        values.put("live_threads", Math.max(0, ManagementFactory.getThreadMXBean().getThreadCount()));
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        values.put("gc_count_cumulative", gcCount);
        values.put("gc_time_ms_cumulative", gcMs);
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            double cpu = extended.getProcessCpuLoad();
            if (Double.isFinite(cpu) && cpu >= 0 && cpu <= 1) values.put("process_cpu_load", cpu);
        }
        return Map.copyOf(values);
    }

    private Map<String, Integer> poolSnapshot() {
        if (!(dataSource instanceof HikariDataSource hikari)) return Map.of();
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) return Map.of();
        return Map.of("active", pool.getActiveConnections(), "idle", pool.getIdleConnections(),
                "pending", pool.getThreadsAwaitingConnection(), "max", hikari.getMaximumPoolSize());
    }
}
