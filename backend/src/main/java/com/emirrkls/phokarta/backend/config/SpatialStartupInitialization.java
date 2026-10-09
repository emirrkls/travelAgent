package com.emirrkls.phokarta.backend.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.geolatte.geom.crs.CrsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Deterministic library-only initialization, before context refresh and Boot readiness. */
@Component
@Lazy(false)
public final class SpatialStartupInitialization implements SmartInitializingSingleton {
    private static final Logger LOG = LoggerFactory.getLogger(SpatialStartupInitialization.class);
    private static final Initialization PROCESS = new Initialization(
            SpatialStartupInitialization::initializeCrs, System::nanoTime);
    private final MeterRegistry registry;
    private final Initialization initialization;

    @Autowired
    public SpatialStartupInitialization(MeterRegistry registry) {
        this(registry, PROCESS);
    }

    SpatialStartupInitialization(MeterRegistry registry, Initialization initialization) {
        this.registry = registry;
        this.initialization = initialization;
    }

    @Override
    public void afterSingletonsInstantiated() {
        initialization.initialize(registry);
    }

    static void initializeCrs() {
        // One public lookup, no fallback or catalogue data. Geolatte 1.9.1 itself loads
        // spatial_ref_sys.txt and parses its registry in CrsRegistry's static initializer.
        Objects.requireNonNull(CrsRegistry.getGeographicCoordinateReferenceSystemForEPSG(4326),
                "Required EPSG:4326 registry entry unavailable");
    }

    /** Single-flight, sticky success/failure across application contexts in this JVM. */
    static final class Initialization {
        private final Runnable action;
        private final LongSupplier nanoTime;
        private boolean completed;
        private Throwable failure;

        Initialization(Runnable action, LongSupplier nanoTime) {
            this.action = action;
            this.nanoTime = nanoTime;
        }

        synchronized void initialize(MeterRegistry registry) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (completed) return;
            long start = nanoTime.getAsLong();
            LOG.atInfo().addKeyValue("spatial_startup_state", "started")
                    .log("spatial startup initialization");
            try {
                action.run();
                completed = true;
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                long elapsed = Math.max(0, nanoTime.getAsLong() - start);
                String outcome = completed ? "completed" : "failed";
                // Deliberately no exception message, resource contents, credentials or user data.
                LOG.atInfo().addKeyValue("spatial_startup_state", outcome)
                        .addKeyValue("duration_ms", elapsed / 1_000_000.0)
                        .log("spatial startup initialization");
                Timer.builder("phokarta.spatial.startup.duration")
                        .description("One-time deterministic CRS initialization; not a performance gate")
                        .tag("outcome", outcome).register(registry)
                        .record(elapsed, TimeUnit.NANOSECONDS);
            }
        }
    }
}
