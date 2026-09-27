package io.github.omith786.chat.server.room;

import io.github.omith786.chat.protocol.ChatRules;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.cluster.ChatEvent;
import io.github.omith786.chat.server.cluster.MessageBroker;
import io.github.omith786.chat.server.message.MessageService;
import io.github.omith786.chat.server.web.ChatException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomAndMessageServiceTest {

    @Autowired
    RoomService rooms;

    @Autowired
    MessageService messages;

    @Autowired
    MessageBroker broker;

    @Test
    void seededRoomsExist() {
        assertThat(rooms.list()).extracting(RoomView::id).startsWith("general", "random");
    }

    @Test
    void createDerivesTheIdFromTheNameAndPublishesAnEvent() {
        List<ChatEvent> events = capture();

        RoomView room = rooms.create("alice", null, "  Service Test  Room ", " A description ");

        assertThat(room.id()).isEqualTo("service-test-room");
        assertThat(room.name()).isEqualTo("Service Test Room");
        assertThat(room.description()).isEqualTo("A description");
        assertThat(room.createdBy()).isEqualTo("alice");
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(ChatEvent.RoomCreated.class,
                created -> assertThat(created.room().id()).isEqualTo("service-test-room")));
    }

    @Test
    void duplicateAndInvalidRoomsAreRejected() {
        rooms.create("alice", "dup-room", "Duplicate", "");

        assertThatThrownBy(() -> rooms.create("bob", "dup-room", "Duplicate again", ""))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ROOM_EXISTS));
        assertThatThrownBy(() -> rooms.create("bob", "Bad Id!", "Name", ""))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID));
        assertThatThrownBy(() -> rooms.create("bob", null, "x", ""))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID));
        assertThatThrownBy(() -> rooms.requireExists("does-not-exist"))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_SUCH_ROOM));
    }

    @Test
    void roomMessagesAreStoredCleanedAndPublished() {
        rooms.create("alice", "store-room", "Store room", "");
        List<ChatEvent> events = capture();

        ServerFrame.RoomMessage stored = messages.postToRoom("store-room", "alice", "  hello\u0007 world  ");

        assertThat(stored.id()).isPositive();
        assertThat(stored.content()).isEqualTo("hello world");
        assertThat(stored.room()).isEqualTo("store-room");
        assertThat(events).contains(new ChatEvent.RoomMessagePosted(
                ((ChatEvent.RoomMessagePosted) events.getLast()).origin(), stored));
        assertThat(messages.roomHistory("store-room", null, null)).containsExactly(stored);
    }

    @Test
    void historyPagesBackwardsInChronologicalOrder() {
        rooms.create("alice", "paging-room", "Paging room", "");
        for (int i = 1; i <= 7; i++) {
            messages.postToRoom("paging-room", "alice", "message " + i);
        }

        List<ServerFrame.RoomMessage> latest = messages.roomHistory("paging-room", null, 3);
        assertThat(latest).extracting(ServerFrame.RoomMessage::content)
                .containsExactly("message 5", "message 6", "message 7");

        List<ServerFrame.RoomMessage> older = messages.roomHistory("paging-room", latest.getFirst().id(), 3);
        assertThat(older).extracting(ServerFrame.RoomMessage::content)
                .containsExactly("message 2", "message 3", "message 4");

        assertThat(messages.roomHistory("paging-room", older.getFirst().id(), 3))
                .extracting(ServerFrame.RoomMessage::content).containsExactly("message 1");
    }

    @Test
    void historyLimitIsBounded() {
        assertThatThrownBy(() -> messages.roomHistory("general", null, 0)).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messages.roomHistory("general", null, 101)).isInstanceOf(ChatException.class);
    }

    @Test
    void directMessagesShareOneConversationInBothDirections() {
        ServerFrame.DirectMessage first = messages.postDirect("dm-ann", "dm-ben", "hi ben");
        ServerFrame.DirectMessage reply = messages.postDirect("dm-ben", "dm-ann", "hi ann");
        messages.postDirect("dm-ann", "dm-cat", "hello cat");

        assertThat(messages.directHistory("dm-ann", "dm-ben", null, null)).containsExactly(first, reply);
        assertThat(messages.directHistory("dm-ben", "dm-ann", null, null)).containsExactly(first, reply);
        assertThat(messages.directHistory("dm-cat", "dm-ben", null, null)).isEmpty();

        assertThat(messages.conversations("dm-ann"))
                .extracting(MessageService.Conversation::with, c -> c.last().content())
                .containsExactly(
                        tuple("dm-cat", "hello cat"),
                        tuple("dm-ben", "hi ann"));
        assertThat(messages.conversations("dm-ben")).hasSize(1);
    }

    @Test
    void emptyOrOversizedMessagesAreRejected() {
        assertThatThrownBy(() -> messages.postToRoom("general", "alice", " \n "))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID));
        assertThatThrownBy(() -> messages.postDirect("alice", "bob", "x".repeat(ChatRules.MAX_MESSAGE_LENGTH + 1)))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID));
    }

    private List<ChatEvent> capture() {
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        broker.subscribe(events::add);
        return events;
    }
}
