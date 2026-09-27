package io.github.omith786.chat.server.cluster;

import java.util.function.Consumer;

/**
 * The default broker: delivers events within this JVM only. Correct and free for a single
 * instance; with several instances, users on different instances would not see each other.
 */
public final class InMemoryMessageBroker implements MessageBroker {

    private final LocalSubscribers subscribers = new LocalSubscribers();

    @Override
    public void publish(ChatEvent event) {
        subscribers.deliver(event);
    }

    @Override
    public void subscribe(Consumer<ChatEvent> listener) {
        subscribers.add(listener);
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
