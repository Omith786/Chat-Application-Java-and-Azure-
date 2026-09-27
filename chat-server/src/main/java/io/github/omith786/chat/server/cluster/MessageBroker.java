package io.github.omith786.chat.server.cluster;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Fans {@link ChatEvent}s out to every server instance, including the one that published them.
 *
 * <p>Implementations must deliver an instance's own events to its local subscribers
 * synchronously, before {@link #publish} returns. The WebSocket handler relies on this: after
 * publishing a "joined" event it immediately reads the updated room membership.
 */
public interface MessageBroker {

    /** Delivers {@code event} to local subscribers now, and to other instances if there are any. */
    void publish(ChatEvent event);

    /** Registers a listener for events from this and every other instance. */
    void subscribe(Consumer<ChatEvent> listener);

    /** Short name for logs and the health endpoint, e.g. {@code local}. */
    String type();

    /** Whether the broker can currently reach the other instances. */
    boolean isConnected();

    /** Extra, non-secret details for the health endpoint. */
    default Map<String, Object> details() {
        return Map.of();
    }
}
