package io.github.omith786.chat.server.message;

import io.github.omith786.chat.protocol.ServerFrame;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A stored message. Room messages and direct messages share one table, keyed by
 * {@code channel} ({@code room:<id>} or {@code dm:<a>:<b>}), so history paging is the same query
 * for both.
 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String channel;

    @Column(name = "room_id", length = 32)
    private String roomId;

    @Column(nullable = false, length = 20)
    private String sender;

    @Column(length = 20)
    private String recipient;

    // 2,000 code points can need up to 4,000 UTF-16 units.
    @Column(nullable = false, length = 4000)
    private String content;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected ChatMessage() {
        // for JPA
    }

    private ChatMessage(String channel, String roomId, String sender, String recipient, String content, Instant sentAt) {
        this.channel = channel;
        this.roomId = roomId;
        this.sender = sender;
        this.recipient = recipient;
        this.content = content;
        this.sentAt = sentAt;
    }

    static ChatMessage forRoom(String channel, String roomId, String sender, String content, Instant sentAt) {
        return new ChatMessage(channel, roomId, sender, null, content, sentAt);
    }

    static ChatMessage direct(String channel, String sender, String recipient, String content, Instant sentAt) {
        return new ChatMessage(channel, null, sender, recipient, content, sentAt);
    }

    public Long getId() {
        return id;
    }

    public String getChannel() {
        return channel;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getContent() {
        return content;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    ServerFrame.RoomMessage toRoomFrame() {
        return new ServerFrame.RoomMessage(id, roomId, sender, content, sentAt);
    }

    ServerFrame.DirectMessage toDirectFrame() {
        return new ServerFrame.DirectMessage(id, sender, recipient, content, sentAt);
    }
}
