package io.github.omith786.chat.server.cluster;

import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.ws.ConnectionRegistry;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Adds instance and cluster figures to {@code /actuator/info}. */
@Component
public class ChatInfoContributor implements InfoContributor {

    private final ServerInstance instance;
    private final MessageBroker broker;
    private final ConnectionRegistry connections;
    private final PresenceRegistry presence;

    public ChatInfoContributor(ServerInstance instance, MessageBroker broker, ConnectionRegistry connections,
                               PresenceRegistry presence) {
        this.instance = instance;
        this.broker = broker;
        this.connections = connections;
        this.presence = presence;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("chat", Map.of(
                "instance", instance.id(),
                "broker", broker.type(),
                "localConnections", connections.size(),
                "onlineUsers", presence.onlineUsers().size(),
                "knownInstances", presence.knownInstances().size()));
    }
}
