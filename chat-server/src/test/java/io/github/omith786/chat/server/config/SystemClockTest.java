package io.github.omith786.chat.server.config;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SystemClockTest {

    /** Timestamps must match what the database can store, or live and stored messages differ. */
    @RepeatedTest(20)
    void ticksInWholeMicroseconds() {
        Instant now = CoreConfig.systemClock().instant();
        assertThat(now.getNano() % 1_000).isZero();
    }
}
