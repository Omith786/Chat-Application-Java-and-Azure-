package io.github.omith786.chat.server.presence;

import io.github.omith786.chat.server.config.ServerInstance;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cluster-wide view of who is online and which rooms they are in.
 *
 * <p>The registry keeps a separate view per server instance (user to rooms). A user is online if
 * any instance lists them, and in a room if any instance lists them in it. Keeping views per
 * instance, rather than one merged set, is what makes the model robust: a snapshot from an
 * instance simply replaces that instance's view, and an instance that stops sending heartbeats
 * can be dropped as a whole without disturbing users held elsewhere.
 *
 * <p>Every mutator returns the resulting cluster-wide {@link PresenceDelta}s, computed by diffing
 * the affected users before and after the change, so callers never have to reason about which
 * instance a user was on.
 */
@Component
public class PresenceRegistry {

    private final String localInstance;
    private final Clock clock;
    private final Map<String, InstanceView> views = new HashMap<>();

    public PresenceRegistry(ServerInstance instance, Clock clock) {
        this.localInstance = instance.id();
        this.clock = clock;
    }

    /** {@code user} opened their first connection on {@code instance}. */
    public synchronized List<PresenceDelta> userConnected(String instance, String user) {
        return change(Set.of(user), () -> view(instance).users.putIfAbsent(user, new HashSet<>()));
    }

    /** {@code user} closed their last connection on {@code instance}. */
    public synchronized List<PresenceDelta> userDisconnected(String instance, String user) {
        return change(Set.of(user), () -> view(instance).users.remove(user));
    }

    /** {@code user} joined {@code room} on {@code instance} (implies they are connected there). */
    public synchronized List<PresenceDelta> roomJoined(String instance, String room, String user) {
        return change(Set.of(user), () -> view(instance).users.computeIfAbsent(user, u -> new HashSet<>()).add(room));
    }

    /** {@code user} left {@code room} on {@code instance}. */
    public synchronized List<PresenceDelta> roomLeft(String instance, String room, String user) {
        return change(Set.of(user), () -> {
            Set<String> rooms = view(instance).users.get(user);
            if (rooms != null) {
                rooms.remove(room);
            }
        });
    }

    /** Replaces everything known about {@code instance} with a full snapshot. */
    public synchronized List<PresenceDelta> replace(String instance, Map<String, Set<String>> users) {
        InstanceView view = view(instance);
        Set<String> affected = new HashSet<>(view.users.keySet());
        affected.addAll(users.keySet());
        return change(affected, () -> {
            view.users.clear();
            users.forEach((user, rooms) -> view.users.put(user, new HashSet<>(rooms)));
        });
    }

    /** Forgets {@code instance} entirely, e.g. when it announces a clean shutdown. */
    public synchronized List<PresenceDelta> removeInstance(String instance) {
        InstanceView view = views.get(instance);
        if (view == null || instance.equals(localInstance)) {
            return List.of();
        }
        return change(new HashSet<>(view.users.keySet()), () -> views.remove(instance));
    }

    /**
     * Drops every other instance that has not been heard from since {@code cutoff}. The local
     * instance never expires: its view is maintained directly.
     */
    public synchronized List<PresenceDelta> expireSilentInstances(Instant cutoff) {
        List<PresenceDelta> deltas = new ArrayList<>();
        for (String instance : List.copyOf(views.keySet())) {
            if (!instance.equals(localInstance) && views.get(instance).lastSeen.isBefore(cutoff)) {
                deltas.addAll(removeInstance(instance));
            }
        }
        return deltas;
    }

    /** Whether {@code user} is connected to any instance. */
    public synchronized boolean isOnline(String user) {
        return roomsOf(user).isPresent();
    }

    /** Everyone online, sorted. */
    public synchronized List<String> onlineUsers() {
        Set<String> users = new TreeSet<>();
        views.values().forEach(view -> users.addAll(view.users.keySet()));
        return List.copyOf(users);
    }

    /** Everyone in {@code room} on any instance, sorted. */
    public synchronized List<String> membersOf(String room) {
        Set<String> members = new TreeSet<>();
        for (InstanceView view : views.values()) {
            view.users.forEach((user, rooms) -> {
                if (rooms.contains(room)) {
                    members.add(user);
                }
            });
        }
        return List.copyOf(members);
    }

    /** Number of online users in each room that has any. */
    public synchronized Map<String, Integer> memberCounts() {
        Map<String, Set<String>> byRoom = new HashMap<>();
        for (InstanceView view : views.values()) {
            view.users.forEach((user, rooms) ->
                    rooms.forEach(room -> byRoom.computeIfAbsent(room, r -> new HashSet<>()).add(user)));
        }
        Map<String, Integer> counts = new HashMap<>();
        byRoom.forEach((room, users) -> counts.put(room, users.size()));
        return counts;
    }

    /** Instances currently known, including this one. */
    public synchronized Set<String> knownInstances() {
        return Set.copyOf(views.keySet());
    }

    private InstanceView view(String instance) {
        InstanceView view = views.computeIfAbsent(instance, id -> new InstanceView());
        view.lastSeen = clock.instant();
        return view;
    }

    /** Cluster-wide rooms for a user, or empty if the user is offline everywhere. */
    private Optional<Set<String>> roomsOf(String user) {
        Set<String> rooms = null;
        for (InstanceView view : views.values()) {
            Set<String> instanceRooms = view.users.get(user);
            if (instanceRooms != null) {
                if (rooms == null) {
                    rooms = new TreeSet<>();
                }
                rooms.addAll(instanceRooms);
            }
        }
        return Optional.ofNullable(rooms);
    }

    private List<PresenceDelta> change(Collection<String> affectedUsers, Runnable mutation) {
        Map<String, Optional<Set<String>>> before = new HashMap<>();
        for (String user : affectedUsers) {
            before.put(user, roomsOf(user));
        }
        mutation.run();

        List<PresenceDelta> deltas = new ArrayList<>();
        for (String user : new TreeSet<>(affectedUsers)) {
            Optional<Set<String>> was = before.get(user);
            Optional<Set<String>> now = roomsOf(user);
            Set<String> wasRooms = was.orElse(Set.of());
            Set<String> nowRooms = now.orElse(Set.of());

            // Order matters to clients: come online before joining, leave rooms before going offline.
            if (was.isEmpty() && now.isPresent()) {
                deltas.add(new PresenceDelta.UserOnline(user));
            }
            for (String room : new TreeSet<>(wasRooms)) {
                if (!nowRooms.contains(room)) {
                    deltas.add(new PresenceDelta.RoomLeft(room, user));
                }
            }
            for (String room : new TreeSet<>(nowRooms)) {
                if (!wasRooms.contains(room)) {
                    deltas.add(new PresenceDelta.RoomJoined(room, user));
                }
            }
            if (was.isPresent() && now.isEmpty()) {
                deltas.add(new PresenceDelta.UserOffline(user));
            }
        }
        return deltas;
    }

    private static final class InstanceView {
        private final Map<String, Set<String>> users = new HashMap<>();
        private Instant lastSeen = Instant.EPOCH;
    }
}
