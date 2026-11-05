package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.RateLimitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RateLimitService {
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds, long resetEpochSeconds) { }

    private record Window(long startedAtMillis, int requests) { }

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitService(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public RateLimitService(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        int limit = Math.max(1, properties.getRequestsPerWindow());
        long windowMillis = Duration.ofSeconds(Math.max(1, properties.getWindowSeconds())).toMillis();
        long now = clock.millis();
        AtomicReference<Decision> decision = new AtomicReference<>();

        windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAtMillis() >= windowMillis || now < current.startedAtMillis()) {
                long reset = Instant.ofEpochMilli(now + windowMillis).getEpochSecond();
                decision.set(new Decision(true, limit, limit - 1, 0, reset));
                return new Window(now, 1);
            }

            long retryAfter = Math.max(1, (long) Math.ceil((current.startedAtMillis() + windowMillis - now) / 1000.0));
            long reset = Instant.ofEpochMilli(current.startedAtMillis() + windowMillis).getEpochSecond();
            if (current.requests() >= limit) {
                decision.set(new Decision(false, limit, 0, retryAfter, reset));
                return current;
            }

            int count = current.requests() + 1;
            decision.set(new Decision(true, limit, limit - count, 0, reset));
            return new Window(current.startedAtMillis(), count);
        });
        return decision.get();
    }

    public boolean isEnabled() { return properties.isEnabled(); }
}
