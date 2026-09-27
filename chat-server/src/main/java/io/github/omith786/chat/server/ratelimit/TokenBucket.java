package io.github.omith786.chat.server.ratelimit;

/**
 * A classic token bucket: holds up to {@code capacity} tokens, gains one every
 * {@code nanosPerToken}, and each permitted action spends one.
 *
 * <p>Time is passed in by the caller (as {@link System#nanoTime()} values), which keeps the class
 * deterministic under test.
 */
final class TokenBucket {

    private final long capacity;
    private final long nanosPerToken;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(long capacity, long nanosPerToken, long nowNanos) {
        this.capacity = capacity;
        this.nanosPerToken = nanosPerToken;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    /** Spends one token if available. */
    synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** A full bucket is indistinguishable from a new one, so it can be discarded to save memory. */
    synchronized boolean isFull(long nowNanos) {
        refill(nowNanos);
        return tokens >= capacity;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + (double) elapsed / nanosPerToken);
            lastRefillNanos = nowNanos;
        }
    }
}
