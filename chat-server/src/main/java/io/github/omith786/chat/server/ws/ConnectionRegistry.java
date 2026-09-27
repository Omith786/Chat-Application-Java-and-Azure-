package io.github.omith786.chat.server.ws;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The connections held by this instance, indexed by user and by room.
 *
 * <p>Methods report "first" and "last" transitions (a user's first connection, the last of their
 * connections to leave a room and so on) because those are exactly the moments other instances
 * need to hear about.
 */
@Component
public class ConnectionRegistry {

    private final Map<String, ClientConnection> byId = new HashMap<>();
    private final Map<String, Set<ClientConnection>> byUser = new HashMap<>();
    private final Map<String, Set<ClientConnection>> byRoom = new HashMap<>();

    /** Outcome of {@link #authenticate}. */
    public enum AuthResult { FIRST_CONNECTION, ADDITIONAL_CONNECTION, TOO_MANY_CONNECTIONS }

    /** Outcome of {@link #join}. */
    public enum JoinResult { FIRST_FOR_USER, JOINED, ALREADY_JOINED, TOO_MANY_ROOMS }

    /**
     * What closing a connection changed.
     *
     * @param user           the connection's user, or {@code null} if it never authenticated
     * @param lastConnection whether that was the user's last connection on this instance
     * @param roomsLeft      rooms the user is no longer in on this instance
     */
    public record Removal(String user, boolean lastConnection, List<String> roomsLeft) {
    }

    public synchronized void add(ClientConnection connection) {
        byId.put(connection.id(), connection);
    }

    /** Binds a connection to a user, unless the user already has {@code maxPerUser} connections. */
    public synchronized AuthResult authenticate(ClientConnection connection, String user, int maxPerUser) {
        Set<ClientConnection> existing = byUser.getOrDefault(user, Set.of());
        if (existing.size() >= maxPerUser) {
            return AuthResult.TOO_MANY_CONNECTIONS;
        }
        connection.setUser(user);
        byUser.computeIfAbsent(user, u -> new LinkedHashSet<>()).add(connection);
        return existing.isEmpty() ? AuthResult.FIRST_CONNECTION : AuthResult.ADDITIONAL_CONNECTION;
    }

    /** Adds an authenticated connection to a room. */
    public synchronized JoinResult join(ClientConnection connection, String room, int maxRooms) {
        if (connection.rooms().contains(room)) {
            return JoinResult.ALREADY_JOINED;
        }
        if (connection.rooms().size() >= maxRooms) {
            return JoinResult.TOO_MANY_ROOMS;
        }
        boolean userAlreadyThere = userInRoom(connection.user(), room);
        connection.rooms().add(room);
        byRoom.computeIfAbsent(room, r -> new LinkedHashSet<>()).add(connection);
        return userAlreadyThere ? JoinResult.JOINED : JoinResult.FIRST_FOR_USER;
    }

    /**
     * Removes a connection from a room.
     *
     * @return {@code true} if the user now has no connection left in the room
     */
    public synchronized boolean leave(ClientConnection connection, String room) {
        if (!connection.rooms().remove(room)) {
            return false;
        }
        removeFromRoomIndex(connection, room);
        return !userInRoom(connection.user(), room);
    }

    /** Forgets a closed connection. Safe to call more than once. */
    public synchronized Removal remove(ClientConnection connection) {
        if (byId.remove(connection.id()) == null) {
            return new Removal(null, false, List.of());
        }
        String user = connection.user();
        Set<String> rooms = new TreeSet<>(connection.rooms());
        // Clear first, so userInRoom below only sees the user's other connections.
        connection.rooms().clear();
        List<String> roomsLeft = new ArrayList<>();
        for (String room : rooms) {
            removeFromRoomIndex(connection, room);
            if (user != null && !userInRoom(user, room)) {
                roomsLeft.add(room);
            }
        }
        if (user == null) {
            return new Removal(null, false, List.of());
        }
        Set<ClientConnection> userConnections = byUser.get(user);
        userConnections.remove(connection);
        boolean last = userConnections.isEmpty();
        if (last) {
            byUser.remove(user);
        }
        return new Removal(user, last, roomsLeft);
    }

    /** Connections that have joined {@code room}. */
    public synchronized List<ClientConnection> inRoom(String room) {
        return List.copyOf(byRoom.getOrDefault(room, Set.of()));
    }

    /** Connections belonging to {@code user}. */
    public synchronized List<ClientConnection> ofUser(String user) {
        return List.copyOf(byUser.getOrDefault(user, Set.of()));
    }

    /** Every authenticated connection. */
    public synchronized List<ClientConnection> authenticated() {
        List<ClientConnection> all = new ArrayList<>();
        byUser.values().forEach(all::addAll);
        return all;
    }

    /** Number of open connections, authenticated or not. */
    public synchronized int size() {
        return byId.size();
    }

    /** This instance's users and the union of their connections' rooms, for presence snapshots. */
    public synchronized Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> users = new TreeMap<>();
        byUser.forEach((user, connections) -> {
            Set<String> rooms = new TreeSet<>();
            connections.forEach(c -> rooms.addAll(c.rooms()));
            users.put(user, rooms);
        });
        return users;
    }

    private boolean userInRoom(String user, String room) {
        for (ClientConnection c : byUser.getOrDefault(user, Set.of())) {
            if (c.rooms().contains(room)) {
                return true;
            }
        }
        return false;
    }

    private void removeFromRoomIndex(ClientConnection connection, String room) {
        Set<ClientConnection> members = byRoom.get(room);
        if (members != null) {
            members.remove(connection);
            if (members.isEmpty()) {
                byRoom.remove(room);
            }
        }
    }

    // Visible for tests.
    synchronized Set<String> roomsIndexed() {
        return new HashSet<>(byRoom.keySet());
    }
}
