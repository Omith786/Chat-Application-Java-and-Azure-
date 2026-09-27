package io.github.omith786.chat.server.ratelimit;

import io.github.omith786.chat.server.config.ChatProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One token bucket per key (a username or a client IP). Limits are per server instance: a
 * WebSocket connection lives on a single instance, so per-user message limits are exact, while
 * per-IP login limits are approximate when scaled out.
 */
public final class RateLimiter {

    private final long capacity;
    private final long nanosPerToken;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param bucket    capacity and refill period
     * @param nanoClock monotonic time source, normally {@code System::nanoTime}
     */
    public RateLimiter(ChatProperties.Bucket bucket, LongSupplier nanoClock) {
        this.capacity = bucket.capacity();
        this.nanosPerToken = bucket.refillPeriod().toNanos();
        this.nanoClock = nanoClock;
    }

    /** Returns {@code true} and spends a token if {@code key} is within its limit. */
    public boolean tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        return buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, nanosPerToken, now)).tryConsume(now);
    }

    /** Drops buckets that have refilled completely; returns how many were removed. */
    public int evictIdle() {
        long now = nanoClock.getAsLong();
        int before = buckets.size();
        buckets.values().removeIf(bucket -> bucket.isFull(now));
        return before - buckets.size();
    }

    /** Number of keys currently tracked. */
    public int size() {
        return buckets.size();
    }
}
