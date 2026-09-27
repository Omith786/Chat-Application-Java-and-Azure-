package io.github.omith786.chat.server.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.omith786.chat.protocol.ChatRules;
import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.CloseCodes;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.auth.SessionTokenService;
import io.github.omith786.chat.server.cluster.ChatEvent;
import io.github.omith786.chat.server.cluster.MessageBroker;
import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.message.MessageService;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.ratelimit.RateLimits;
import io.github.omith786.chat.server.room.RoomService;
import io.github.omith786.chat.server.web.ChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;

/**
 * The chat WebSocket endpoint ({@code /ws}). Speaks the JSON protocol defined by
 * {@link ClientFrame} and {@link ServerFrame}.
 *
 * <p>Lifecycle of a connection: it must send {@link ClientFrame.Auth} within the configured
 * timeout, then may join rooms, post, send direct messages and typing signals. Anything that
 * changes what other instances need to know (first connection, first join, last leave, last
 * disconnect) is published to the {@link MessageBroker}.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String CONNECTION_ATTRIBUTE = ClientConnection.class.getName();

    // A client that cannot absorb 256 KB of queued frames within 10 seconds is too slow to keep.
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;

    private final ConnectionRegistry connections;
    private final LocalDelivery delivery;
    private final FrameCodec codec;
    private final SessionTokenService tokens;
    private final MessageBroker broker;
    private final PresenceRegistry presence;
    private final RoomService rooms;
    private final MessageService messages;
    private final RateLimits rateLimits;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final String self;
    private final ChatProperties.Limits limits;

    public ChatWebSocketHandler(ConnectionRegistry connections, LocalDelivery delivery, FrameCodec codec,
                                SessionTokenService tokens, MessageBroker broker, PresenceRegistry presence,
                                RoomService rooms, MessageService messages, RateLimits rateLimits,
                                TaskScheduler chatScheduler, Clock clock, ServerInstance instance,
                                ChatProperties properties) {
        this.connections = connections;
        this.delivery = delivery;
        this.codec = codec;
        this.tokens = tokens;
        this.broker = broker;
        this.presence = presence;
        this.rooms = rooms;
        this.messages = messages;
        this.rateLimits = rateLimits;
        this.scheduler = chatScheduler;
        this.clock = clock;
        this.self = instance.id();
        this.limits = properties.limits();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        ClientConnection connection = new ClientConnection(
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        session.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        connections.add(connection);
        scheduler.schedule(() -> {
            if (connection.isOpen() && !connection.isAuthenticated()) {
                connection.close(new CloseStatus(CloseCodes.AUTH_TIMEOUT, "Authentication timed out"));
            }
        }, clock.instant().plus(limits.authTimeout()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientConnection connection = connectionOf(session);
        ClientFrame frame;
        try {
            frame = codec.decode(message.getPayload());
        } catch (JsonProcessingException e) {
            delivery.send(connection, new ServerFrame.Error(null, ErrorCode.BAD_FRAME,
                    "Frames must be JSON objects with a known \"type\""));
            return;
        }
        try {
            handle(connection, frame);
        } catch (ChatException e) {
            delivery.send(connection, new ServerFrame.Error(refOf(frame), e.code(), e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Failed to handle {} from {}", frame.getClass().getSimpleName(), connection.user(), e);
            delivery.send(connection, new ServerFrame.Error(refOf(frame), ErrorCode.INTERNAL, "Something went wrong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectionRegistry.Removal removal = connections.remove(connectionOf(session));
        if (removal.user() == null) {
            return;
        }
        for (String room : removal.roomsLeft()) {
            broker.publish(new ChatEvent.RoomLeft(self, room, removal.user()));
        }
        if (removal.lastConnection()) {
            broker.publish(new ChatEvent.UserDisconnected(self, removal.user()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on {}: {}", session.getId(), exception.toString());
    }

    private void handle(ClientConnection connection, ClientFrame frame) {
        if (frame instanceof ClientFrame.Auth auth) {
            authenticate(connection, auth);
            return;
        }
        if (!connection.isAuthenticated()) {
            throw new ChatException(ErrorCode.UNAUTHORISED, "Send an auth frame first");
        }
        switch (frame) {
            case ClientFrame.Join join -> join(connection, join.room());
            case ClientFrame.Leave leave -> leave(connection, leave.room());
            case ClientFrame.RoomMessage msg -> postToRoom(connection, msg);
            case ClientFrame.DirectMessage msg -> postDirect(connection, msg);
            case ClientFrame.Typing typing -> typing(connection, typing);
            case ClientFrame.Ping ping -> delivery.send(connection, new ServerFrame.Pong());
            case ClientFrame.Auth auth -> throw new IllegalStateException("handled above");
        }
    }

    private void authenticate(ClientConnection connection, ClientFrame.Auth auth) {
        if (connection.isAuthenticated()) {
            throw new ChatException(ErrorCode.INVALID, "Already authenticated");
        }
        String user = tokens.verify(auth.token()).orElse(null);
        if (user == null) {
            delivery.send(connection, new ServerFrame.Error(null, ErrorCode.UNAUTHORISED,
                    "Invalid or expired session token, please sign in again"));
            connection.close(new CloseStatus(CloseCodes.INVALID_TOKEN, "Invalid session token"));
            return;
        }
        switch (connections.authenticate(connection, user, limits.connectionsPerUser())) {
            case TOO_MANY_CONNECTIONS -> {
                delivery.send(connection, new ServerFrame.Error(null, ErrorCode.LIMIT_REACHED,
                        "Too many open connections for " + user));
                connection.close(new CloseStatus(CloseCodes.TOO_MANY_CONNECTIONS, "Too many connections"));
                return;
            }
            case FIRST_CONNECTION -> broker.publish(new ChatEvent.UserConnected(self, user));
            case ADDITIONAL_CONNECTION -> {
                // Already known to be online; nothing to announce.
            }
        }
        delivery.send(connection, new ServerFrame.Welcome(user, presence.onlineUsers(), self));
    }

    private void join(ClientConnection connection, String room) {
        requireRoomId(room);
        rooms.requireExists(room);
        switch (connections.join(connection, room, limits.roomsPerConnection())) {
            case TOO_MANY_ROOMS -> throw new ChatException(ErrorCode.LIMIT_REACHED,
                    "A connection can join at most " + limits.roomsPerConnection() + " rooms");
            case FIRST_FOR_USER -> broker.publish(new ChatEvent.RoomJoined(self, room, connection.user()));
            case JOINED, ALREADY_JOINED -> {
                // The user was already in the room on this instance.
            }
        }
        delivery.send(connection, new ServerFrame.Joined(room, presence.membersOf(room)));
    }

    private void leave(ClientConnection connection, String room) {
        requireRoomId(room);
        if (connections.leave(connection, room)) {
            broker.publish(new ChatEvent.RoomLeft(self, room, connection.user()));
        }
        delivery.send(connection, new ServerFrame.Left(room));
    }

    private void postToRoom(ClientConnection connection, ClientFrame.RoomMessage msg) {
        rateLimits.checkMessage(connection.user());
        requireRoomId(msg.room());
        if (!connection.rooms().contains(msg.room())) {
            throw new ChatException(ErrorCode.NOT_IN_ROOM, "Join '" + msg.room() + "' before posting to it");
        }
        ServerFrame.RoomMessage stored = messages.postToRoom(msg.room(), connection.user(), msg.content());
        delivery.send(connection, new ServerFrame.Ack(msg.ref(), stored.id()));
    }

    private void postDirect(ClientConnection connection, ClientFrame.DirectMessage msg) {
        rateLimits.checkMessage(connection.user());
        String to = recipient(connection, msg.to());
        if (!presence.isOnline(to)) {
            throw new ChatException(ErrorCode.USER_OFFLINE, to + " is not online");
        }
        ServerFrame.DirectMessage stored = messages.postDirect(connection.user(), to, msg.content());
        delivery.send(connection, new ServerFrame.Ack(msg.ref(), stored.id()));
    }

    private void typing(ClientConnection connection, ClientFrame.Typing typing) {
        if ((typing.room() == null) == (typing.to() == null)) {
            throw new ChatException(ErrorCode.INVALID, "A typing frame needs exactly one of \"room\" or \"to\"");
        }
        // Typing signals are ephemeral: over the limit, or aimed somewhere pointless, they are dropped quietly.
        if (!rateLimits.allowTyping(connection.user())) {
            return;
        }
        if (typing.room() != null) {
            if (connection.rooms().contains(typing.room())) {
                broker.publish(new ChatEvent.Typing(self, connection.user(), typing.room(), null));
            }
        } else {
            String to = recipient(connection, typing.to());
            if (presence.isOnline(to)) {
                broker.publish(new ChatEvent.Typing(self, connection.user(), null, to));
            }
        }
    }

    private static String recipient(ClientConnection connection, String raw) {
        String to;
        try {
            to = ChatRules.normaliseUsername(raw);
        } catch (IllegalArgumentException e) {
            throw ChatException.invalid(e);
        }
        if (to.equals(connection.user())) {
            throw new ChatException(ErrorCode.INVALID, "You cannot send a direct message to yourself");
        }
        return to;
    }

    private static void requireRoomId(String room) {
        if (!ChatRules.isValidRoomId(room)) {
            throw new ChatException(ErrorCode.INVALID, "Room ids are 1 to 32 lower-case letters, digits or hyphens");
        }
    }

    private static String refOf(ClientFrame frame) {
        return switch (frame) {
            case ClientFrame.RoomMessage m -> m.ref();
            case ClientFrame.DirectMessage m -> m.ref();
            default -> null;
        };
    }

    private static ClientConnection connectionOf(WebSocketSession session) {
        return (ClientConnection) session.getAttributes().get(CONNECTION_ATTRIBUTE);
    }
}
