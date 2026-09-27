package io.github.omith786.chat.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;

/**
 * A frame sent from the server to a client over the chat WebSocket.
 *
 * <p>Like {@link ClientFrame}, the {@code type} property selects the variant.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerFrame.Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = ServerFrame.Joined.class, name = "joined"),
        @JsonSubTypes.Type(value = ServerFrame.Left.class, name = "left"),
        @JsonSubTypes.Type(value = ServerFrame.RoomMessage.class, name = "message"),
        @JsonSubTypes.Type(value = ServerFrame.DirectMessage.class, name = "direct"),
        @JsonSubTypes.Type(value = ServerFrame.Presence.class, name = "presence"),
        @JsonSubTypes.Type(value = ServerFrame.Membership.class, name = "membership"),
        @JsonSubTypes.Type(value = ServerFrame.Typing.class, name = "typing"),
        @JsonSubTypes.Type(value = ServerFrame.RoomCreated.class, name = "room_created"),
        @JsonSubTypes.Type(value = ServerFrame.Ack.class, name = "ack"),
        @JsonSubTypes.Type(value = ServerFrame.Error.class, name = "error"),
        @JsonSubTypes.Type(value = ServerFrame.Pong.class, name = "pong")
})
public sealed interface ServerFrame {

    /**
     * Sent once after a successful {@link ClientFrame.Auth}.
     *
     * @param user     the authenticated username
     * @param online   everyone currently online across all server instances
     * @param instance id of the server instance holding this connection (useful when scaled out)
     */
    record Welcome(String user, List<String> online, String instance) implements ServerFrame {
    }

    /** Confirms a {@link ClientFrame.Join}, with the users currently in the room. */
    record Joined(String room, List<String> members) implements ServerFrame {
    }

    /** Confirms a {@link ClientFrame.Leave}. */
    record Left(String room) implements ServerFrame {
    }

    /** A message posted to a room. {@code id} is unique and increases over time. */
    record RoomMessage(long id, String room, String from, String content, Instant sentAt) implements ServerFrame {
    }

    /** A private message; delivered to both the sender's and the recipient's connections. */
    record DirectMessage(long id, String from, String to, String content, Instant sentAt) implements ServerFrame {
    }

    /** A user came online (first connection anywhere) or went offline (last connection closed). */
    record Presence(String user, boolean online) implements ServerFrame {
    }

    /** A user entered or left a room the receiving connection has joined. */
    record Membership(String room, String user, boolean joined) implements ServerFrame {
    }

    /** Someone is typing in a room ({@code room} set) or to this user ({@code to} set). */
    record Typing(String from, String room, String to) implements ServerFrame {
    }

    /** A new room was created and can now be joined. */
    record RoomCreated(String id, String name, String description, String createdBy) implements ServerFrame {
    }

    /** A message was stored and delivered; {@code id} is its message id. */
    record Ack(String ref, long id) implements ServerFrame {
    }

    /**
     * A request was rejected. {@code code} is stable and machine-readable (see {@link ErrorCode});
     * {@code message} is for people.
     */
    record Error(String ref, String code, String message) implements ServerFrame {
    }

    /** Reply to {@link ClientFrame.Ping}. */
    record Pong() implements ServerFrame {
    }
}
