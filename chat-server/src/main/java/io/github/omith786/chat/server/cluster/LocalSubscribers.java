package io.github.omith786.chat.server.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The in-process half of every broker: a list of listeners that are called in order, with one
 * failing listener unable to stop the others.
 */
public final class LocalSubscribers {

    private static final Logger log = LoggerFactory.getLogger(LocalSubscribers.class);

    private final List<Consumer<ChatEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Adds a listener. */
    public void add(Consumer<ChatEvent> listener) {
        listeners.add(listener);
    }

    /** Calls every listener with {@code event}. */
    public void deliver(ChatEvent event) {
        for (Consumer<ChatEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.error("Listener failed on {}", event.getClass().getSimpleName(), e);
            }
        }
    }
}
