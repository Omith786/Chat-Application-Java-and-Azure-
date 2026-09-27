package io.github.omith786.chat.server.room;

import io.github.omith786.chat.protocol.ChatRules;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.cluster.ChatEvent;
import io.github.omith786.chat.server.cluster.MessageBroker;
import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.web.ChatException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Creating, listing and looking up rooms. */
@Service
public class RoomService {

    private final RoomRepository rooms;
    private final PresenceRegistry presence;
    private final MessageBroker broker;
    private final ServerInstance instance;
    private final Clock clock;
    private final int maxRooms;

    public RoomService(RoomRepository rooms, PresenceRegistry presence, MessageBroker broker,
                       ServerInstance instance, Clock clock, ChatProperties properties) {
        this.rooms = rooms;
        this.presence = presence;
        this.broker = broker;
        this.instance = instance;
        this.clock = clock;
        this.maxRooms = properties.limits().maxRooms();
    }

    /** Every room with its current online count. */
    @Transactional(readOnly = true)
    public List<RoomView> list() {
        Map<String, Integer> counts = presence.memberCounts();
        return rooms.findAllByOrderByCreatedAtAscIdAsc().stream()
                .map(room -> RoomView.of(room, counts.getOrDefault(room.getId(), 0)))
                .toList();
    }

    /** One room, or {@code no_such_room}. */
    @Transactional(readOnly = true)
    public RoomView get(String id) {
        Room room = rooms.findById(requireValidId(id))
                .orElseThrow(() -> noSuchRoom(id));
        return RoomView.of(room, presence.membersOf(room.getId()).size());
    }

    /** Throws {@code no_such_room} unless the room exists. */
    @Transactional(readOnly = true)
    public void requireExists(String id) {
        if (!rooms.existsById(requireValidId(id))) {
            throw noSuchRoom(id);
        }
    }

    /**
     * Creates a room and tells every connected client about it.
     *
     * @param requestedId optional id; derived from the name when blank
     */
    @Transactional
    public RoomView create(String creator, String requestedId, String rawName, String rawDescription) {
        String name;
        String description;
        String id;
        try {
            name = ChatRules.normaliseRoomName(rawName);
            description = ChatRules.normaliseRoomDescription(rawDescription);
            id = requestedId == null || requestedId.isBlank() ? ChatRules.slugify(name) : requestedId.strip();
        } catch (IllegalArgumentException e) {
            throw ChatException.invalid(e);
        }
        requireValidId(id);
        if (rooms.existsById(id)) {
            throw new ChatException(ErrorCode.ROOM_EXISTS, "A room with id '" + id + "' already exists");
        }
        if (rooms.count() >= maxRooms) {
            throw new ChatException(ErrorCode.LIMIT_REACHED, "The server already has the maximum number of rooms");
        }
        Room room;
        try {
            room = rooms.saveAndFlush(new Room(id, name, description, creator, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // Another instance created the same id between our check and our insert.
            throw new ChatException(ErrorCode.ROOM_EXISTS, "A room with id '" + id + "' already exists");
        }
        broker.publish(new ChatEvent.RoomCreated(instance.id(),
                new ServerFrame.RoomCreated(room.getId(), room.getName(), room.getDescription(), room.getCreatedBy())));
        return RoomView.of(room, 0);
    }

    private static String requireValidId(String id) {
        if (!ChatRules.isValidRoomId(id)) {
            throw new ChatException(ErrorCode.INVALID,
                    "Room ids are 1 to 32 lower-case letters, digits or hyphens");
        }
        return id;
    }

    private static ChatException noSuchRoom(String id) {
        return new ChatException(ErrorCode.NO_SUCH_ROOM, "No room called '" + id + "'");
    }
}
