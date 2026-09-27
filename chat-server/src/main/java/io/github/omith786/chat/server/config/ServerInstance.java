package io.github.omith786.chat.server.config;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Identity of this server process within a cluster. A restarted process gets a new id, which is
 * what lets the other instances forget the users it used to hold.
 *
 * @param id unique, stable for the lifetime of the process
 */
public record ServerInstance(String id) {

    /** Uses the configured id, or generates {@code srv-<8 hex chars>} when none is set. */
    public static ServerInstance of(String configured) {
        if (configured != null && !configured.isBlank()) {
            return new ServerInstance(configured.strip());
        }
        byte[] bytes = new byte[4];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new ServerInstance("srv-" + HexFormat.of().formatHex(bytes));
    }
}
