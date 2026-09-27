package io.github.omith786.chat.server.message;

import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Message history. Pages are returned oldest first; pass the smallest id you have as
 * {@code before} to fetch the page preceding it.
 */
@RestController
@RequestMapping("/api")
public class HistoryController {

    private final MessageService messages;

    public HistoryController(MessageService messages) {
        this.messages = messages;
    }

    /** History of a room. */
    @GetMapping("/rooms/{roomId}/messages")
    public List<ServerFrame.RoomMessage> roomHistory(@PathVariable String roomId,
                                                     @RequestParam(required = false) Long before,
                                                     @RequestParam(required = false) Integer limit) {
        return messages.roomHistory(roomId, before, limit);
    }

    /** The caller's direct conversations, most recently active first. */
    @GetMapping("/direct")
    public List<MessageService.Conversation> conversations(@CurrentUser String user) {
        return messages.conversations(user);
    }

    /** History of the caller's conversation with {@code other}. */
    @GetMapping("/direct/{other}/messages")
    public List<ServerFrame.DirectMessage> directHistory(@CurrentUser String user,
                                                         @PathVariable String other,
                                                         @RequestParam(required = false) Long before,
                                                         @RequestParam(required = false) Integer limit) {
        return messages.directHistory(user, other, before, limit);
    }
}
