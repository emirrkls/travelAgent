package com.emirrkls.phokarta.backend.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.geolatte.geom.G2D;
import org.geolatte.geom.crs.CrsRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpatialStartupInitializationTest {
    @Test
    void callbacksAndSeparateContextsShareExactlyOneSuccessfulInitialization() {
        var count = new AtomicInteger();
        var state = new SpatialStartupInitialization.Initialization(count::incrementAndGet, System::nanoTime);
        var registry = new SimpleMeterRegistry();
        var first = new SpatialStartupInitialization(registry, state);
        var second = new SpatialStartupInitialization(registry, state);
        first.afterSingletonsInstantiated();
        first.afterSingletonsInstantiated();
        second.afterSingletonsInstantiated();
        assertThat(count).hasValue(1);
        assertThat(registry.get("phokarta.spatial.startup.duration").tag("outcome", "completed")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void concurrentCallbacksAreSingleFlight() {
        var count = new AtomicInteger();
        var state = new SpatialStartupInitialization.Initialization(count::incrementAndGet, System::nanoTime);
        var registry = new SimpleMeterRegistry();
        CompletableFuture.allOf(java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.runAsync(() -> state.initialize(registry)))
                .toArray(CompletableFuture[]::new)).join();
        assertThat(count).hasValue(1);
    }

    @Test
    void runtimeFailureIsStickyAndNotRetried() {
        var count = new AtomicInteger();
        var failure = new IllegalStateException("private diagnostic must not be logged");
        var state = new SpatialStartupInitialization.Initialization(() -> {
            count.incrementAndGet(); throw failure;
        }, System::nanoTime);
        var registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> state.initialize(registry)).isSameAs(failure);
        assertThatThrownBy(() -> state.initialize(registry)).isSameAs(failure);
        assertThat(count).hasValue(1);
        assertThat(registry.get("phokarta.spatial.startup.duration").tag("outcome", "failed")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("phokarta.spatial.startup.duration").tag("outcome", "completed").timer())
                .isNull();
    }

    @Test
    void classInitializationErrorAlsoFailsClosedWithoutRetry() {
        var failure = new ExceptionInInitializerError("test resource unavailable");
        var state = new SpatialStartupInitialization.Initialization(() -> { throw failure; }, System::nanoTime);
        var registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> state.initialize(registry)).isSameAs(failure);
        assertThatThrownBy(() -> state.initialize(registry)).isSameAs(failure);
    }

    @Test
    void logsAreSafeAndDurationUsesMonotonicClock() {
        Logger logger = (Logger) LoggerFactory.getLogger(SpatialStartupInitialization.class);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        try {
            var clock = new AtomicInteger();
            var state = new SpatialStartupInitialization.Initialization(() -> {},
                    () -> clock.getAndIncrement() == 0 ? 1_000_000 : 4_000_000);
            var registry = new SimpleMeterRegistry();
            state.initialize(registry);
            assertThat(capture.list).hasSize(2);
            assertThat(capture.list.getLast().getKeyValuePairs().toString())
                    .contains("completed", "3.0").doesNotContain("password", "user", "spatial_ref_sys");
            assertThat(registry.get("phokarta.spatial.startup.duration").timer()
                    .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3);
        } finally { logger.detachAppender(capture); capture.stop(); }
    }

    @Test
    void realLibraryLookupHasNoDatabaseOrNetworkDependencyAndKeepsWgs84() {
        // Only the library and its bundled resource are needed; no Spring, datasource,
        // HTTP client or importer is constructed by this production initialization action.
        SpatialStartupInitialization.initializeCrs();
        var crs = CrsRegistry.getGeographicCoordinateReferenceSystemForEPSG(4326);
        assertThat(crs.getPositionClass()).isEqualTo(G2D.class);
        assertThat(crs.getCrsId().getCode()).isEqualTo(4326);
        assertThat(SpatialStartupInitialization.class.getConstructors()).hasSize(1);
        assertThat(SpatialStartupInitialization.class.getConstructors()[0].getParameterTypes())
                .containsExactly(io.micrometer.core.instrument.MeterRegistry.class);
    }

    @Test
    void readinessRemainsRefusingUntilInitializationCompletes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var states = new java.util.concurrent.CopyOnWriteArrayList<ReadinessState>();
        var state = new SpatialStartupInitialization.Initialization(() -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test synchronization failed");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(error);
            }
        }, System::nanoTime);
        var app = application(state, context, states);
        var running = CompletableFuture.supplyAsync(app::run);
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(context.get().getBean(ApplicationAvailabilityBean.class).getReadinessState())
                    .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            assertThat(states).doesNotContain(ReadinessState.ACCEPTING_TRAFFIC);
            release.countDown();
            try (var ready = running.get(10, TimeUnit.SECONDS)) {
                assertThat(ready.getBean(ApplicationAvailabilityBean.class).getReadinessState())
                        .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            }
        } finally { release.countDown(); }
    }

    @Test
    void failureAbortsBootWithoutAnyAcceptingReadinessEvent() {
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var states = new ArrayList<ReadinessState>();
        var failure = new IllegalStateException("required library unavailable");
        var state = new SpatialStartupInitialization.Initialization(() -> { throw failure; }, System::nanoTime);
        assertThatThrownBy(() -> application(state, context, states).run()).isSameAs(failure);
        assertThat(states).doesNotContain(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(context.get().isActive()).isFalse();
    }

    private SpringApplication application(SpatialStartupInitialization.Initialization state,
                                          AtomicReference<ConfigurableApplicationContext> context,
                                          List<ReadinessState> states) {
        var app = new SpringApplication(LibraryOnlyConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
        app.addInitializers(ctx -> {
            context.set(ctx);
            ((BeanDefinitionRegistry) ctx.getBeanFactory()).registerBeanDefinition("spatialStartupInitialization",
                    BeanDefinitionBuilder.genericBeanDefinition(SpatialStartupInitialization.class,
                            () -> new SpatialStartupInitialization(new SimpleMeterRegistry(), state))
                            .getBeanDefinition());
        });
        app.addListeners(event -> {
            if (event instanceof AvailabilityChangeEvent<?> change
                    && change.getState() instanceof ReadinessState readiness) states.add(readiness);
        });
        return app;
    }

    @Configuration(proxyBeanMethods = false)
    static class LibraryOnlyConfiguration {
        @Bean ApplicationAvailabilityBean availability() { return new ApplicationAvailabilityBean(); }
    }
}
