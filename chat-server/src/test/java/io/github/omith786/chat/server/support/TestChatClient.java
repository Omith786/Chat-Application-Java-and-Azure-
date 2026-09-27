package io.github.omith786.chat.server.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.ProtocolJson;
import io.github.omith786.chat.protocol.ServerFrame;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A deliberately low-level WebSocket client for tests: it can send malformed frames, skip
 * authentication and inspect close codes, which the real terminal client never does.
 */
public final class TestChatClient implements AutoCloseable {

    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = ProtocolJson.newMapper();

    private final WebSocket socket;
    private final LinkedBlockingQueue<ServerFrame> inbox = new LinkedBlockingQueue<>();
    private final List<ServerFrame> pending = new ArrayList<>();
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private volatile String token;

    private TestChatClient(URI uri, Map<String, String> headers) {
        WebSocket.Builder builder = HTTP.newWebSocketBuilder();
        headers.forEach(builder::header);
        try {
            this.socket = builder.buildAsync(uri, new Listener()).orTimeout(5, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException handshake) {
                throw new AssertionError("Handshake with " + uri + " failed: HTTP "
                        + handshake.getResponse().statusCode() + " " + handshake.getResponse().body(), e);
            }
            throw e;
        }
    }

    /** Opens an unauthenticated connection to {@code ws://localhost:<port>/ws}. */
    public static TestChatClient open(int port) {
        return new TestChatClient(URI.create("ws://localhost:" + port + "/ws"), Map.of());
    }

    /** Signs {@code username} in over REST, connects and waits for the welcome frame. */
    public static TestChatClient connectAs(int port, String username) {
        TestChatClient client = open(port);
        client.token = createSession(port, username);
        client.send(new ClientFrame.Auth(client.token));
        client.await(ServerFrame.Welcome.class);
        return client;
    }

    /** Creates a session over REST and returns its token. */
    public static String createSession(int port, String username) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\"}"))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new AssertionError("Session creation failed: " + response.statusCode() + " " + response.body());
            }
            return MAPPER.readTree(response.body()).get("token").asText();
        } catch (Exception e) {
            throw new AssertionError("Session creation failed", e);
        }
    }

    /** The session token used by {@link #connectAs}, for opening a second connection as the same user. */
    public String token() {
        return token;
    }

    public void send(ClientFrame frame) {
        try {
            sendRaw(MAPPER.writeValueAsString(frame));
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    public void sendRaw(String text) {
        socket.sendText(text, true).orTimeout(5, TimeUnit.SECONDS).join();
    }

    /** Joins a room and waits for the confirmation. */
    public ServerFrame.Joined join(String room) {
        send(new ClientFrame.Join(room));
        return await(ServerFrame.Joined.class, j -> j.room().equals(room));
    }

    /** Waits for the next frame of {@code type}. */
    public <T extends ServerFrame> T await(Class<T> type) {
        return await(type, frame -> true);
    }

    /**
     * Waits for a frame of {@code type} matching {@code condition}. Frames that do not match are
     * kept (in order) for later calls, so tests need not depend on the order of unrelated frames.
     */
    public synchronized <T extends ServerFrame> T await(Class<T> type, Predicate<T> condition) {
        for (int i = 0; i < pending.size(); i++) {
            ServerFrame frame = pending.get(i);
            if (type.isInstance(frame) && condition.test(type.cast(frame))) {
                pending.remove(i);
                return type.cast(frame);
            }
        }
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                ServerFrame frame = remaining > 0 ? inbox.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (frame == null) {
                    throw new AssertionError("Timed out waiting for " + type.getSimpleName() + "; unconsumed " + pending);
                }
                if (type.isInstance(frame) && condition.test(type.cast(frame))) {
                    return type.cast(frame);
                }
                pending.add(frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * Returns every unconsumed frame plus any that arrive within {@code window}, for asserting
     * that something did <em>not</em> happen.
     */
    public synchronized List<ServerFrame> drain(Duration window) {
        List<ServerFrame> frames = new ArrayList<>(pending);
        pending.clear();
        long deadline = System.nanoTime() + window.toNanos();
        try {
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                ServerFrame frame = inbox.poll(remaining, TimeUnit.NANOSECONDS);
                if (frame != null) {
                    frames.add(frame);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return frames;
    }

    /** Waits for the server to close the connection and returns the close code. */
    public int awaitClose() {
        return closeCode.orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    public boolean isClosed() {
        return closeCode.isDone();
    }

    @Override
    public void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").orTimeout(2, TimeUnit.SECONDS).exceptionally(e -> null).join();
        }
        // Wait briefly for the server to process the close, so later assertions see a consistent state.
        closeCode.completeOnTimeout(-1, 2, TimeUnit.SECONDS).join();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                try {
                    inbox.add(MAPPER.readValue(buffer.toString(), ServerFrame.class));
                } catch (JsonProcessingException e) {
                    throw new AssertionError("Server sent an unparseable frame: " + buffer, e);
                } finally {
                    buffer.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.complete(1006);
        }
    }
}
