package io.github.omith786.chat.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A frame sent from a client to the server over the chat WebSocket.
 *
 * <p>Every frame is a JSON object whose {@code type} property selects the variant, for example
 * {@code {"type":"message","room":"general","content":"hi"}}. The optional {@code ref} on
 * message frames is echoed back in the matching {@link ServerFrame.Ack} or
 * {@link ServerFrame.Error}, so a client can correlate replies with what it sent.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientFrame.Auth.class, name = "auth"),
        @JsonSubTypes.Type(value = ClientFrame.Join.class, name = "join"),
        @JsonSubTypes.Type(value = ClientFrame.Leave.class, name = "leave"),
        @JsonSubTypes.Type(value = ClientFrame.RoomMessage.class, name = "message"),
        @JsonSubTypes.Type(value = ClientFrame.DirectMessage.class, name = "direct"),
        @JsonSubTypes.Type(value = ClientFrame.Typing.class, name = "typing"),
        @JsonSubTypes.Type(value = ClientFrame.Ping.class, name = "ping")
})
public sealed interface ClientFrame {

    /** First frame on every connection: proves the username with a session token. */
    record Auth(String token) implements ClientFrame {
        @Override
        public String toString() {
            // Keeps tokens out of logs if a frame is ever printed.
            return "Auth[token=***]";
        }
    }

    /** Subscribes this connection to a room. */
    record Join(String room) implements ClientFrame {
    }

    /** Unsubscribes this connection from a room. */
    record Leave(String room) implements ClientFrame {
    }

    /** Posts a message to a room the connection has joined. */
    record RoomMessage(String room, String content, String ref) implements ClientFrame {
    }

    /** Sends a private message to another online user. */
    record DirectMessage(String to, String content, String ref) implements ClientFrame {
    }

    /** Signals that the user is typing, either in a room or to one user (exactly one is set). */
    record Typing(String room, String to) implements ClientFrame {
    }

    /** Application-level keep-alive; answered with {@link ServerFrame.Pong}. */
    record Ping() implements ClientFrame {
    }
}
