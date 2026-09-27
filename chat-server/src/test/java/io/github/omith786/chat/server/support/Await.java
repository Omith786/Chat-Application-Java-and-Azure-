package io.github.omith786.chat.server.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls a condition until it holds, for state that settles asynchronously. */
public final class Await {

    private Await() {
    }

    /** Waits up to five seconds for {@code condition}. */
    public static void until(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting until " + description);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
