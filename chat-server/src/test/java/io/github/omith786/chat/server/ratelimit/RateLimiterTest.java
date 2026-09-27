package io.github.omith786.chat.server.ratelimit;

import io.github.omith786.chat.server.config.ChatProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final RateLimiter limiter = new RateLimiter(
            ChatProperties.Bucket.validated(3, Duration.ofSeconds(1)), nanos::get);

    @Test
    void allowsABurstUpToCapacityThenRefusesUntilRefilled() {
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();

        advance(Duration.ofMillis(999));
        assertThat(limiter.tryAcquire("alice")).isFalse();
        advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void refillNeverExceedsCapacity() {
        advance(Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("alice")).isTrue();
        }
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("alice");
        }
        assertThat(limiter.tryAcquire("alice")).isFalse();
        assertThat(limiter.tryAcquire("bob")).isTrue();
    }

    @Test
    void evictsOnlyBucketsThatHaveFullyRefilled() {
        limiter.tryAcquire("alice");
        limiter.tryAcquire("bob");
        limiter.tryAcquire("bob");
        limiter.tryAcquire("bob");

        advance(Duration.ofSeconds(1));
        assertThat(limiter.evictIdle()).isEqualTo(1); // alice is full again, bob is not
        assertThat(limiter.size()).isEqualTo(1);

        advance(Duration.ofSeconds(2));
        assertThat(limiter.evictIdle()).isEqualTo(1);
        assertThat(limiter.size()).isZero();
    }

    @Test
    void bucketSettingsAreValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatProperties.Bucket.validated(0, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatProperties.Bucket.validated(1, Duration.ZERO));
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
