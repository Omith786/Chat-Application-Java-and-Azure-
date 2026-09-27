package io.github.omith786.chat.server.ws;

import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.presence.PresenceDelta;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import java.util.Collection;
import java.util.List;

/** Sends frames to the connections held by this instance. Each frame is serialised once. */
@Component
public class LocalDelivery {

    private final ConnectionRegistry connections;
    private final FrameCodec codec;

    public LocalDelivery(ConnectionRegistry connections, FrameCodec codec) {
        this.connections = connections;
        this.codec = codec;
    }

    /** To every connection in {@code room}, optionally skipping one user's connections. */
    public void toRoom(String room, ServerFrame frame, String excludeUser) {
        TextMessage message = codec.encode(frame);
        for (ClientConnection connection : connections.inRoom(room)) {
            if (excludeUser == null || !excludeUser.equals(connection.user())) {
                connection.send(message);
            }
        }
    }

    /** To every connection of each listed user. */
    public void toUsers(Collection<String> users, ServerFrame frame) {
        TextMessage message = codec.encode(frame);
        for (String user : users) {
            connections.ofUser(user).forEach(connection -> connection.send(message));
        }
    }

    /** To every authenticated connection. */
    public void toAll(ServerFrame frame) {
        TextMessage message = codec.encode(frame);
        connections.authenticated().forEach(connection -> connection.send(message));
    }

    /** To one connection. */
    public void send(ClientConnection connection, ServerFrame frame) {
        connection.send(codec.encode(frame));
    }

    /** Tells clients about cluster-wide presence changes. */
    public void presence(List<PresenceDelta> deltas) {
        for (PresenceDelta delta : deltas) {
            switch (delta) {
                case PresenceDelta.UserOnline d -> toAll(new ServerFrame.Presence(d.user(), true));
                case PresenceDelta.UserOffline d -> toAll(new ServerFrame.Presence(d.user(), false));
                case PresenceDelta.RoomJoined d -> toRoom(d.room(), new ServerFrame.Membership(d.room(), d.user(), true), null);
                case PresenceDelta.RoomLeft d -> toRoom(d.room(), new ServerFrame.Membership(d.room(), d.user(), false), null);
            }
        }
    }
}
