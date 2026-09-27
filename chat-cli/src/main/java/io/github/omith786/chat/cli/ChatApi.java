package io.github.omith786.chat.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.omith786.chat.protocol.ProtocolJson;
import io.github.omith786.chat.protocol.ServerFrame;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A small client for the chat server's REST API, built on {@link HttpClient}. */
public final class ChatApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final URI base;
    private final ObjectMapper mapper = ProtocolJson.newMapper();
    private volatile String token;

    /**
     * @param base server root, e.g. {@code http://localhost:8080}
     */
    public ChatApi(HttpClient http, URI base) {
        this.http = http;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
    }

    /** The server root this client talks to. */
    public URI base() {
        return base;
    }

    /** The WebSocket endpoint derived from the base URI ({@code http} to {@code ws}, {@code https} to {@code wss}). */
    public URI webSocketUri() {
        String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + base.getRawAuthority() + base.getRawPath() + "ws");
    }

    /** Signs in and remembers the token for later calls. */
    public Session createSession(String username) {
        Session session = send(post("api/sessions", Map.of("username", username)), new TypeReference<>() { });
        token = session.token();
        return session;
    }

    /** Uses an existing token (e.g. after reconnecting). */
    public void useToken(String token) {
        this.token = token;
    }

    /** The current token, or {@code null} before signing in. */
    public String token() {
        return token;
    }

    /** All rooms. */
    public List<Room> rooms() {
        return send(get("api/rooms"), new TypeReference<>() { });
    }

    /** Creates a room; the server derives its id from the name. */
    public Room createRoom(String name, String description) {
        return send(post("api/rooms", Map.of("name", name, "description", description == null ? "" : description)),
                new TypeReference<>() { });
    }

    /** The latest {@code limit} messages of a room, oldest first. */
    public List<ServerFrame.RoomMessage> roomHistory(String room, int limit) {
        return send(get("api/rooms/" + encode(room) + "/messages?limit=" + limit), new TypeReference<>() { });
    }

    /** The latest {@code limit} messages exchanged with {@code user}, oldest first. */
    public List<ServerFrame.DirectMessage> directHistory(String user, int limit) {
        return send(get("api/direct/" + encode(user) + "/messages?limit=" + limit), new TypeReference<>() { });
    }

    /** Everyone online. */
    public List<String> onlineUsers() {
        return send(get("api/users/online"), new TypeReference<>() { });
    }

    /** Everyone in a room. */
    public List<String> roomMembers(String room) {
        return send(get("api/rooms/" + encode(room) + "/members"), new TypeReference<>() { });
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
        String current = token;
        if (current != null) {
            builder.header("Authorization", "Bearer " + current);
        }
        return builder;
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest post(String path, Object body) {
        try {
            return request(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise request body", e);
        }
    }

    private <T> T send(HttpRequest request, TypeReference<T> type) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ChatApiException(0, null, "Cannot reach " + base + " (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatApiException(0, null, "Interrupted");
        }
        try {
            if (response.statusCode() / 100 != 2) {
                throw problem(response);
            }
            return mapper.readValue(response.body(), type);
        } catch (JsonProcessingException e) {
            throw new ChatApiException(response.statusCode(), null, "Unexpected response from server");
        }
    }

    private ChatApiException problem(HttpResponse<String> response) {
        String code = null;
        String detail = "HTTP " + response.statusCode();
        try {
            JsonNode body = mapper.readTree(response.body());
            if (body != null && body.hasNonNull("detail")) {
                detail = body.get("detail").asText();
            }
            if (body != null && body.hasNonNull("code")) {
                code = body.get("code").asText();
            }
        } catch (JsonProcessingException ignored) {
            // Not a problem-details body (e.g. a proxy error page); keep the status line.
        }
        return new ChatApiException(response.statusCode(), code, detail);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A signed-in session. */
    public record Session(String username, String token, Instant expiresAt) {
    }

    /** A room as listed by the server. */
    public record Room(String id, String name, String description, String createdBy, int online) {
    }
}
