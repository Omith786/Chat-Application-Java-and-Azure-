package io.github.omith786.chat.server.room;

import java.time.Instant;

/**
 * A room as returned by the REST API.
 *
 * @param online number of users currently in the room across all instances
 */
public record RoomView(String id, String name, String description, String createdBy, Instant createdAt, int online) {

    static RoomView of(Room room, int online) {
        return new RoomView(room.getId(), room.getName(), room.getDescription(), room.getCreatedBy(),
                room.getCreatedAt(), online);
    }
}
