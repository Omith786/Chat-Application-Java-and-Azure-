package io.github.omith786.chat.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.ProtocolJson;
import io.github.omith786.chat.protocol.ServerFrame;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * An authenticated chat WebSocket connection using {@link java.net.http.WebSocket}.
 *
 * <p>{@link #connect} completes only after the server's welcome frame, so a returned connection
 * is ready to join rooms. Incoming frames are handed to a listener on the WebSocket's thread,
 * in order. A ping is sent every 25 seconds so idle connections survive proxies and the
 * server's idle timeout.
 */
public final class ChatConnection implements AutoCloseable {

    static final Duration PING_INTERVAL = Duration.ofSeconds(25);
    /** RFC 6455 code for a connection that dropped without a close frame. */
    public static final int ABNORMAL_CLOSURE = 1006;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper mapper = ProtocolJson.newMapper();
    private final WebSocket socket;
    private final ServerFrame.Welcome welcome;
    private final ScheduledExecutorService pinger;
    private final Object sendLock = new Object();

    private ChatConnection(WebSocket socket, ServerFrame.Welcome welcome) {
        this.socket = socket;
        this.welcome = welcome;
        this.pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-ping");
            thread.setDaemon(true);
            return thread;
        });
        long period = PING_INTERVAL.toMillis();
        pinger.scheduleAtFixedRate(this::pingQuietly, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a connection, authenticates with {@code token} and waits for the welcome frame.
     *
     * @param onFrame called for every frame after the welcome
     * @param onClose called once when the connection ends, with the close code and reason
     * @throws ChatApiException if the server rejects the token or cannot be reached
     */
    public static ChatConnection connect(HttpClient http, URI uri, String token,
                                         Consumer<ServerFrame> onFrame, CloseListener onClose) {
        CompletableFuture<ServerFrame.Welcome> welcome = new CompletableFuture<>();
        Listener listener = new Listener(welcome, onFrame, onClose);
        WebSocket socket;
        try {
            socket = http.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(uri, listener)
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new ChatApiException(0, null, "Cannot open WebSocket to " + uri + " (" + rootMessage(e) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatApiException(0, null, "Interrupted");
        }
        try {
            String auth = ProtocolJson.newMapper().writeValueAsString(new ClientFrame.Auth(token));
            socket.sendText(auth, true).get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            ServerFrame.Welcome received = welcome.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new ChatConnection(socket, received);
        } catch (ExecutionException e) {
            socket.abort();
            if (e.getCause() instanceof ChatApiException rejected) {
                throw rejected;
            }
            throw new ChatApiException(0, null, "Authentication failed (" + rootMessage(e) + ")");
        } catch (TimeoutException | JsonProcessingException e) {
            socket.abort();
            throw new ChatApiException(0, null, "No welcome from server (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            socket.abort();
            throw new ChatApiException(0, null, "Interrupted");
        }
    }

    /** The server's welcome frame (username, who is online, instance id). */
    public ServerFrame.Welcome welcome() {
        return welcome;
    }

    /**
     * Sends a frame and waits until it has been handed to the network. {@code java.net.http}
     * allows only one outstanding send, so sends are serialised here.
     */
    public void send(ClientFrame frame) {
        String json;
        try {
            json = mapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise " + frame, e);
        }
        synchronized (sendLock) {
            try {
                socket.sendText(json, true).get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new ChatApiException(0, null, "Send failed (" + rootMessage(e) + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChatApiException(0, null, "Interrupted");
            }
        }
    }

    /** Whether the connection can still send. */
    public boolean isOpen() {
        return !socket.isOutputClosed() && !socket.isInputClosed();
    }

    /** Closes the connection normally. */
    @Override
    public void close() {
        pinger.shutdownNow();
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(2, TimeUnit.SECONDS).exceptionally(e -> null);
        }
    }

    private void pingQuietly() {
        try {
            if (isOpen()) {
                send(new ClientFrame.Ping());
            }
        } catch (RuntimeException ignored) {
            // A dead connection is reported through the close listener.
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** Called once when a connection ends. */
    @FunctionalInterface
    public interface CloseListener {
        void closed(int code, String reason);
    }

    /** Reassembles fragmented text frames and routes the result. */
    private static final class Listener implements WebSocket.Listener {

        private final ObjectMapper mapper = ProtocolJson.newMapper();
        private final CompletableFuture<ServerFrame.Welcome> welcome;
        private final Consumer<ServerFrame> onFrame;
        private final CloseListener onClose;
        private final StringBuilder buffer = new StringBuilder();
        private boolean closed;

        Listener(CompletableFuture<ServerFrame.Welcome> welcome, Consumer<ServerFrame> onFrame, CloseListener onClose) {
            this.welcome = welcome;
            this.onFrame = onFrame;
            this.onClose = onClose;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                dispatch(json);
            }
            webSocket.request(1);
            return null;
        }

        private void dispatch(String json) {
            ServerFrame frame;
            try {
                frame = mapper.readValue(json, ServerFrame.class);
            } catch (JsonProcessingException e) {
                // A newer server may send frame types this client does not know; skip them.
                return;
            }
            if (!welcome.isDone()) {
                switch (frame) {
                    case ServerFrame.Welcome w -> welcome.complete(w);
                    case ServerFrame.Error error -> welcome.completeExceptionally(
                            new ChatApiException(401, error.code(), error.message()));
                    default -> {
                        // Presence updates can arrive just before the welcome; they are stale by then.
                    }
                }
                return;
            }
            onFrame.accept(frame);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            finish(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            finish(ABNORMAL_CLOSURE, rootMessage(error));
        }

        private synchronized void finish(int code, String reason) {
            if (closed) {
                return;
            }
            closed = true;
            boolean established = welcome.isDone() && !welcome.isCompletedExceptionally();
            welcome.completeExceptionally(new ChatApiException(0, null, "Connection closed: " + code + " " + reason));
            // A connection that never got its welcome is reported by connect() instead.
            if (established) {
                onClose.closed(code, reason);
            }
        }
    }
}
