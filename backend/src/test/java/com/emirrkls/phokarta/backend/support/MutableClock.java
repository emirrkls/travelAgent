package com.emirrkls.phokarta.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Test clock that can be advanced without {@code Thread.sleep}. */
public final class MutableClock extends Clock {
    private final ZoneId zone;
    private final AtomicReference<Instant> instant;

    public MutableClock() {
        this(Instant.now(), ZoneOffset.UTC);
    }

    public MutableClock(Instant instant, ZoneId zone) {
        this.zone = zone;
        this.instant = new AtomicReference<>(instant);
    }

    public void resetToNow() {
        instant.set(Instant.now());
    }

    public void advance(Duration duration) {
        instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
