package io.github.omith786.chat.server.presence;

import io.github.omith786.chat.protocol.ChatRules;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.server.web.ChatException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only presence queries. Live changes are pushed over the WebSocket. */
@RestController
@RequestMapping("/api")
public class PresenceController {

    private final PresenceRegistry presence;

    public PresenceController(PresenceRegistry presence) {
        this.presence = presence;
    }

    /** Everyone online across all instances. */
    @GetMapping("/users/online")
    public List<String> online() {
        return presence.onlineUsers();
    }

    /** Everyone currently in a room. */
    @GetMapping("/rooms/{roomId}/members")
    public List<String> members(@PathVariable String roomId) {
        if (!ChatRules.isValidRoomId(roomId)) {
            throw new ChatException(ErrorCode.INVALID, "Room ids are 1 to 32 lower-case letters, digits or hyphens");
        }
        return presence.membersOf(roomId);
    }
}
