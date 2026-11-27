package io.graphspindle.security;

import io.graphspindle.common.Actor;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets per principal or client address (ARCHITECTURE §8, ADR-033). Single
 * instance only: several replicas would each allow the full rate.
 */
@Component
public class RateLimiter {

    private static final int MAX_BUCKETS = 10_000;

    private final SecurityProperties.RateLimit limits;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(SecurityProperties properties) {
        this.limits = properties.rateLimit();
    }

    /** @throws GraphSpindleException {@code RATE_LIMITED} after the per-minute ingestion budget */
    public void acquireIngest(Actor actor) {
        long wait = tryAcquire("ingest:" + actor.id(), limits.ingestPerMinute());
        if (wait > 0) {
            throw limited("ingestion rate limit exceeded (" + limits.ingestPerMinute() + " per minute)", wait);
        }
    }

    /** Seconds to wait (0 = allowed) for one authenticated request of the principal. */
    long acquireRequest(Actor actor) {
        return tryAcquire("request:" + actor.id(), limits.requestsPerMinute());
    }

    /** Seconds to wait (0 = allowed) before the client address may try another key. */
    long failedAuthWait(String clientAddress) {
        Bucket b = buckets.get("auth:" + clientAddress);
        return b == null ? 0 : b.waitSeconds(limits.failedAuthPerMinute());
    }

    void recordFailedAuth(String clientAddress) {
        tryAcquire("auth:" + clientAddress, limits.failedAuthPerMinute());
    }

    static GraphSpindleException limited(String message, long retryAfterSeconds) {
        return new GraphSpindleException(ErrorCode.RATE_LIMITED, message,
                Map.of("retryAfterSeconds", retryAfterSeconds), null);
    }

    private long tryAcquire(String key, int perMinute) {
        if (buckets.size() > MAX_BUCKETS) {
            buckets.entrySet().removeIf(e -> e.getValue().idle());
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(perMinute)).take(perMinute);
    }

    /** Refills {@code perMinute} tokens per minute, continuously, up to {@code perMinute}. */
    private static final class Bucket {

        private double tokens;
        private long last = System.nanoTime();

        Bucket(int capacity) {
            this.tokens = capacity;
        }

        synchronized long take(int perMinute) {
            refill(perMinute);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return waitFor(perMinute);
        }

        synchronized long waitSeconds(int perMinute) {
            refill(perMinute);
            return tokens >= 1 ? 0 : waitFor(perMinute);
        }

        synchronized boolean idle() {
            return System.nanoTime() - last > 120_000_000_000L;
        }

        private void refill(int perMinute) {
            long now = System.nanoTime();
            tokens = Math.min(perMinute, tokens + (now - last) * perMinute / 60e9);
            last = now;
        }

        private long waitFor(int perMinute) {
            return Math.max(1, (long) Math.ceil((1 - tokens) * 60.0 / Math.max(1, perMinute)));
        }
    }
}
