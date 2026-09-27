package io.github.omith786.chat.server.room;

import io.github.omith786.chat.server.auth.CurrentUser;
import io.github.omith786.chat.server.ratelimit.RateLimits;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST endpoints for rooms. Message history lives in {@code HistoryController}. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService rooms;
    private final RateLimits rateLimits;

    public RoomController(RoomService rooms, RateLimits rateLimits) {
        this.rooms = rooms;
        this.rateLimits = rateLimits;
    }

    /** Lists every room with its online count. */
    @GetMapping
    public List<RoomView> list() {
        return rooms.list();
    }

    /** Returns one room. */
    @GetMapping("/{id}")
    public RoomView get(@PathVariable String id) {
        return rooms.get(id);
    }

    /** Creates a room; the id is derived from the name unless given. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView create(@CurrentUser String user, @RequestBody CreateRoomRequest request) {
        rateLimits.checkRoomCreation(user);
        return rooms.create(user, request.id(), request.name(), request.description());
    }

    /**
     * Body of {@code POST /api/rooms}.
     *
     * @param id          optional room id (slug)
     * @param name        display name
     * @param description optional description
     */
    public record CreateRoomRequest(String id, String name, String description) {
    }
}
