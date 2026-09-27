package io.github.omith786.chat.server.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One client WebSocket connection and its chat state: who it belongs to (once authenticated) and
 * which rooms it has joined. Room membership is per connection, so a user with two tabs open can
 * be in different rooms in each.
 */
public final class ClientConnection {

    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final WebSocketSession session;
    private final Set<String> rooms = ConcurrentHashMap.newKeySet();
    private volatile String user;

    /**
     * @param session a session that is safe for concurrent sends (see
     *                {@code ConcurrentWebSocketSessionDecorator})
     */
    public ClientConnection(WebSocketSession session) {
        this.session = session;
    }

    public String id() {
        return session.getId();
    }

    /** The authenticated username, or {@code null} before the auth frame. */
    public String user() {
        return user;
    }

    public boolean isAuthenticated() {
        return user != null;
    }

    /** Rooms this connection has joined (a live, thread-safe view). */
    public Set<String> rooms() {
        return rooms;
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    void setUser(String user) {
        this.user = user;
    }

    /** Sends a message; a failed send closes the connection rather than propagating. */
    public void send(TextMessage message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(message);
        } catch (IOException | RuntimeException e) {
            // Includes SessionLimitExceededException when a slow client's buffer is full.
            log.debug("Send to {} failed, closing: {}", id(), e.toString());
            close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    /** Closes the connection, ignoring errors from an already-broken socket. */
    public void close(CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Close of {} failed: {}", id(), e.toString());
        }
    }
}
