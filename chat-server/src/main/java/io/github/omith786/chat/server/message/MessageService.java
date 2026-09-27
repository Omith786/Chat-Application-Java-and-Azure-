package io.github.omith786.chat.server.message;

import io.github.omith786.chat.protocol.ChatRules;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.cluster.ChatEvent;
import io.github.omith786.chat.server.cluster.MessageBroker;
import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.room.RoomService;
import io.github.omith786.chat.server.web.ChatException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores messages, publishes them to every instance and serves history.
 *
 * <p>A message is written to the database before it is published, so anything a client sees live
 * is also in history, and message ids (from the database) give a single order across instances.
 * The posting methods are deliberately not {@code @Transactional}: the insert commits inside the
 * repository call, so the event is only published once the row is durable.
 */
@Service
public class MessageService {

    private final MessageRepository messages;
    private final RoomService rooms;
    private final MessageBroker broker;
    private final ServerInstance instance;
    private final Clock clock;
    private final ChatProperties.Limits limits;

    public MessageService(MessageRepository messages, RoomService rooms, MessageBroker broker,
                          ServerInstance instance, Clock clock, ChatProperties properties) {
        this.messages = messages;
        this.rooms = rooms;
        this.broker = broker;
        this.instance = instance;
        this.clock = clock;
        this.limits = properties.limits();
    }

    /** Stores and broadcasts a room message. The caller checks that the sender is in the room. */
    public ServerFrame.RoomMessage postToRoom(String roomId, String sender, String rawContent) {
        String content = cleanContent(rawContent);
        rooms.requireExists(roomId);
        ChatMessage saved = messages.save(
                ChatMessage.forRoom(ChatRules.roomChannel(roomId), roomId, sender, content, clock.instant()));
        ServerFrame.RoomMessage frame = saved.toRoomFrame();
        broker.publish(new ChatEvent.RoomMessagePosted(instance.id(), frame));
        return frame;
    }

    /** Stores and delivers a direct message. The caller checks that the recipient is online. */
    public ServerFrame.DirectMessage postDirect(String sender, String recipient, String rawContent) {
        String content = cleanContent(rawContent);
        ChatMessage saved = messages.save(ChatMessage.direct(
                ChatRules.directChannel(sender, recipient), sender, recipient, content, clock.instant()));
        ServerFrame.DirectMessage frame = saved.toDirectFrame();
        broker.publish(new ChatEvent.DirectMessagePosted(instance.id(), frame));
        return frame;
    }

    /**
     * A page of room history in chronological order.
     *
     * @param beforeId only messages with a smaller id (for paging backwards); {@code null} for the latest
     * @param limit    page size; {@code null} for the configured default
     */
    @Transactional(readOnly = true)
    public List<ServerFrame.RoomMessage> roomHistory(String roomId, Long beforeId, Integer limit) {
        rooms.requireExists(roomId);
        return page(ChatRules.roomChannel(roomId), beforeId, limit).stream()
                .map(ChatMessage::toRoomFrame)
                .toList();
    }

    /** A page of the direct conversation between {@code user} and {@code other}, oldest first. */
    @Transactional(readOnly = true)
    public List<ServerFrame.DirectMessage> directHistory(String user, String other, Long beforeId, Integer limit) {
        requireUsername(other);
        return page(ChatRules.directChannel(user, other), beforeId, limit).stream()
                .map(ChatMessage::toDirectFrame)
                .toList();
    }

    /** Every direct conversation {@code user} has, most recently active first. */
    @Transactional(readOnly = true)
    public List<Conversation> conversations(String user) {
        return messages.findLatestDirectMessagesInvolving(user).stream()
                .map(m -> new Conversation(user.equals(m.getSender()) ? m.getRecipient() : m.getSender(),
                        m.toDirectFrame()))
                .toList();
    }

    private List<ChatMessage> page(String channel, Long beforeId, Integer limit) {
        int size = limit == null ? limits.historyPageSize() : limit;
        if (size < 1 || size > limits.maxHistoryPageSize()) {
            throw new ChatException(ErrorCode.INVALID,
                    "limit must be between 1 and " + limits.maxHistoryPageSize());
        }
        long before = beforeId == null ? Long.MAX_VALUE : beforeId;
        List<ChatMessage> newestFirst = messages.findByChannelAndIdLessThanOrderByIdDesc(channel, before, Limit.of(size));
        List<ChatMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return chronological;
    }

    private static String cleanContent(String raw) {
        try {
            return ChatRules.normaliseContent(raw);
        } catch (IllegalArgumentException e) {
            throw ChatException.invalid(e);
        }
    }

    static void requireUsername(String username) {
        if (!ChatRules.isValidUsername(username)) {
            throw new ChatException(ErrorCode.INVALID, "'" + username + "' is not a valid username");
        }
    }

    /**
     * A direct-message conversation summary.
     *
     * @param with the other participant
     * @param last the most recent message
     */
    public record Conversation(String with, ServerFrame.DirectMessage last) {
    }
}
