package io.github.omith786.chat.server.cluster;

import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.presence.PresenceDelta;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.ws.ConnectionRegistry;
import io.github.omith786.chat.server.ws.LocalDelivery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Receives every {@link ChatEvent} from the broker (this instance's own included) and applies it
 * locally: messages go to the relevant local connections, presence events update the
 * {@link PresenceRegistry} and any resulting cluster-wide changes are pushed to clients.
 *
 * <p>Handling our own events through the same path as remote ones keeps a single code path for
 * delivery, whichever broker is in use.
 */
@Component
public final class ClusterEventHandler implements Consumer<ChatEvent> {

    private final PresenceRegistry presence;
    private final ConnectionRegistry connections;
    private final LocalDelivery delivery;
    private final MessageBroker broker;
    private final String self;

    public ClusterEventHandler(MessageBroker broker, PresenceRegistry presence, ConnectionRegistry connections,
                               LocalDelivery delivery, ServerInstance instance) {
        this.presence = presence;
        this.connections = connections;
        this.delivery = delivery;
        this.broker = broker;
        this.self = instance.id();
        broker.subscribe(this);
    }

    @Override
    public void accept(ChatEvent event) {
        switch (event) {
            case ChatEvent.RoomMessagePosted e -> delivery.toRoom(e.message().room(), e.message(), null);
            case ChatEvent.DirectMessagePosted e ->
                    delivery.toUsers(Set.of(e.message().from(), e.message().to()), e.message());
            case ChatEvent.Typing e -> {
                ServerFrame.Typing frame = new ServerFrame.Typing(e.from(), e.room(), e.to());
                if (e.room() != null) {
                    delivery.toRoom(e.room(), frame, e.from());
                } else if (e.to() != null) {
                    delivery.toUsers(Set.of(e.to()), frame);
                }
            }
            case ChatEvent.RoomCreated e -> delivery.toAll(e.room());
            case ChatEvent.UserConnected e -> apply(presence.userConnected(e.origin(), e.user()));
            case ChatEvent.UserDisconnected e -> apply(presence.userDisconnected(e.origin(), e.user()));
            case ChatEvent.RoomJoined e -> apply(presence.roomJoined(e.origin(), e.room(), e.user()));
            case ChatEvent.RoomLeft e -> apply(presence.roomLeft(e.origin(), e.room(), e.user()));
            case ChatEvent.PresenceSnapshot e -> apply(presence.replace(e.origin(), e.users()));
            case ChatEvent.SnapshotRequested e -> {
                if (!self.equals(e.origin())) {
                    broker.publish(new ChatEvent.PresenceSnapshot(self, connections.snapshot()));
                }
            }
            case ChatEvent.InstanceStopping e -> apply(presence.removeInstance(e.origin()));
        }
    }

    /** Pushes presence changes to local clients; also used by the heartbeat when instances expire. */
    public void apply(List<PresenceDelta> deltas) {
        if (!deltas.isEmpty()) {
            delivery.presence(deltas);
        }
    }
}
