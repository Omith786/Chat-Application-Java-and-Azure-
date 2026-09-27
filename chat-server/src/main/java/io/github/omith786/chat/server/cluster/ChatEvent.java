package io.github.omith786.chat.server.cluster;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.omith786.chat.protocol.ServerFrame;

import java.util.Map;
import java.util.Set;

/**
 * Something that happened on one server instance and must be seen by every instance. Events
 * travel through the {@link MessageBroker}; each carries the id of the instance that produced it
 * ({@code origin}), which receivers use to skip their own echoes and to track presence per
 * instance.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "event")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatEvent.RoomMessagePosted.class, name = "room_message"),
        @JsonSubTypes.Type(value = ChatEvent.DirectMessagePosted.class, name = "direct_message"),
        @JsonSubTypes.Type(value = ChatEvent.Typing.class, name = "typing"),
        @JsonSubTypes.Type(value = ChatEvent.RoomCreated.class, name = "room_created"),
        @JsonSubTypes.Type(value = ChatEvent.UserConnected.class, name = "user_connected"),
        @JsonSubTypes.Type(value = ChatEvent.UserDisconnected.class, name = "user_disconnected"),
        @JsonSubTypes.Type(value = ChatEvent.RoomJoined.class, name = "room_joined"),
        @JsonSubTypes.Type(value = ChatEvent.RoomLeft.class, name = "room_left"),
        @JsonSubTypes.Type(value = ChatEvent.PresenceSnapshot.class, name = "presence_snapshot"),
        @JsonSubTypes.Type(value = ChatEvent.SnapshotRequested.class, name = "snapshot_requested"),
        @JsonSubTypes.Type(value = ChatEvent.InstanceStopping.class, name = "instance_stopping")
})
public sealed interface ChatEvent {

    /** Id of the server instance that published the event. */
    String origin();

    /** A stored room message, ready to deliver. */
    record RoomMessagePosted(String origin, ServerFrame.RoomMessage message) implements ChatEvent {
    }

    /** A stored direct message, ready to deliver. */
    record DirectMessagePosted(String origin, ServerFrame.DirectMessage message) implements ChatEvent {
    }

    /** A typing signal in a room ({@code room} set) or to a user ({@code to} set). */
    record Typing(String origin, String from, String room, String to) implements ChatEvent {
    }

    /** A room was created. */
    record RoomCreated(String origin, ServerFrame.RoomCreated room) implements ChatEvent {
    }

    /** The user's first connection on {@code origin} opened. */
    record UserConnected(String origin, String user) implements ChatEvent {
    }

    /** The user's last connection on {@code origin} closed. */
    record UserDisconnected(String origin, String user) implements ChatEvent {
    }

    /** The user's first connection on {@code origin} joined {@code room}. */
    record RoomJoined(String origin, String room, String user) implements ChatEvent {
    }

    /** The user's last connection on {@code origin} in {@code room} left it. */
    record RoomLeft(String origin, String room, String user) implements ChatEvent {
    }

    /**
     * The complete set of users (and their rooms) connected to {@code origin}. Sent periodically
     * so that receivers converge even if an individual event was lost, and doubles as a heartbeat.
     */
    record PresenceSnapshot(String origin, Map<String, Set<String>> users) implements ChatEvent {
    }

    /** A newly started instance asks the others to send their snapshots now. */
    record SnapshotRequested(String origin) implements ChatEvent {
    }

    /** {@code origin} is shutting down cleanly; forget its users immediately. */
    record InstanceStopping(String origin) implements ChatEvent {
    }
}
