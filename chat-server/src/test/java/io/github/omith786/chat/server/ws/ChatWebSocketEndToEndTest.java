package io.github.omith786.chat.server.ws;

import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.CloseCodes;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.message.MessageService;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.room.RoomService;
import io.github.omith786.chat.server.support.Await;
import io.github.omith786.chat.server.support.TestChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests over a real WebSocket: the server runs on a random port and the clients are
 * {@code java.net.http} WebSockets speaking the JSON protocol.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketEndToEndTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Duration QUIET = Duration.ofMillis(300);

    @LocalServerPort
    int port;

    @Autowired
    RoomService rooms;

    @Autowired
    MessageService messages;

    @Autowired
    PresenceRegistry presence;

    @Test
    void twoClientsExchangeMessagesInARoom() {
        String room = newRoom();
        try (TestChatClient alice = TestChatClient.connectAs(port, name("alice"));
             TestChatClient bob = TestChatClient.connectAs(port, name("bob"))) {
            alice.join(room);
            ServerFrame.Joined bobJoined = bob.join(room);
            assertThat(bobJoined.members()).hasSize(2);

            alice.send(new ClientFrame.RoomMessage(room, "Hello, Bob!", "ref-1"));

            ServerFrame.Ack ack = alice.await(ServerFrame.Ack.class);
            assertThat(ack.ref()).isEqualTo("ref-1");
            ServerFrame.RoomMessage atAlice = alice.await(ServerFrame.RoomMessage.class);
            ServerFrame.RoomMessage atBob = bob.await(ServerFrame.RoomMessage.class);
            assertThat(atBob).isEqualTo(atAlice);
            assertThat(atBob.id()).isEqualTo(ack.id());
            assertThat(atBob.content()).isEqualTo("Hello, Bob!");

            bob.send(new ClientFrame.RoomMessage(room, "Hi Alice", "ref-2"));
            ServerFrame.RoomMessage reply = alice.await(ServerFrame.RoomMessage.class);
            assertThat(reply.content()).isEqualTo("Hi Alice");
            assertThat(reply.id()).isGreaterThan(atBob.id());

            assertThat(messages.roomHistory(room, null, null))
                    .extracting(ServerFrame.RoomMessage::content)
                    .containsExactly("Hello, Bob!", "Hi Alice");
        }
    }

    @Test
    void messagesOnlyReachConnectionsThatJoinedTheRoom() {
        String room = newRoom();
        try (TestChatClient alice = TestChatClient.connectAs(port, name("alice"));
             TestChatClient outsider = TestChatClient.connectAs(port, name("out"))) {
            alice.join(room);
            alice.send(new ClientFrame.RoomMessage(room, "members only", null));
            alice.await(ServerFrame.RoomMessage.class);

            assertThat(outsider.drain(QUIET)).noneMatch(ServerFrame.RoomMessage.class::isInstance);
        }
    }

    @Test
    void leavingARoomStopsDelivery() {
        String room = newRoom();
        try (TestChatClient alice = TestChatClient.connectAs(port, name("alice"));
             TestChatClient bob = TestChatClient.connectAs(port, name("bob"))) {
            alice.join(room);
            bob.join(room);
            bob.send(new ClientFrame.Leave(room));
            bob.await(ServerFrame.Left.class);
            alice.await(ServerFrame.Membership.class, m -> !m.joined());

            alice.send(new ClientFrame.RoomMessage(room, "anyone there?", null));
            alice.await(ServerFrame.RoomMessage.class);
            assertThat(bob.drain(QUIET)).noneMatch(ServerFrame.RoomMessage.class::isInstance);
        }
    }

    @Test
    void presenceAndMembershipChangesArePushed() {
        String room = newRoom();
        String carolName = name("carol");
        try (TestChatClient dave = TestChatClient.connectAs(port, name("dave"))) {
            dave.join(room);
            TestChatClient carol = TestChatClient.connectAs(port, carolName);

            assertThat(dave.await(ServerFrame.Presence.class, p -> p.user().equals(carolName)).online()).isTrue();
            carol.join(room);
            assertThat(dave.await(ServerFrame.Membership.class, m -> m.user().equals(carolName)).joined()).isTrue();

            carol.close();
            ServerFrame.Membership left = dave.await(ServerFrame.Membership.class, m -> m.user().equals(carolName));
            assertThat(left.joined()).isFalse();
            assertThat(dave.await(ServerFrame.Presence.class, p -> p.user().equals(carolName)).online()).isFalse();
            assertThat(presence.isOnline(carolName)).isFalse();
        }
    }

    @Test
    void welcomeListsWhoIsOnline() {
        String first = name("first");
        try (TestChatClient firstClient = TestChatClient.connectAs(port, first);
             TestChatClient second = TestChatClient.open(port)) {
            second.send(new ClientFrame.Auth(TestChatClient.createSession(port, name("second"))));
            ServerFrame.Welcome welcome = second.await(ServerFrame.Welcome.class);
            assertThat(firstClient.isClosed()).isFalse();
            assertThat(welcome.online()).contains(first, welcome.user());
            assertThat(welcome.instance()).startsWith("srv-");
        }
    }

    @Test
    void typingIsRelayedToOthersButNotEchoed() {
        String room = newRoom();
        try (TestChatClient alice = TestChatClient.connectAs(port, name("alice"));
             TestChatClient bob = TestChatClient.connectAs(port, name("bob"))) {
            alice.join(room);
            bob.join(room);

            alice.send(new ClientFrame.Typing(room, null));
            ServerFrame.Typing typing = bob.await(ServerFrame.Typing.class);
            assertThat(typing.room()).isEqualTo(room);
            assertThat(typing.from()).startsWith("alice-");
            assertThat(alice.drain(QUIET)).noneMatch(ServerFrame.Typing.class::isInstance);
        }
    }

    @Test
    void directMessagesReachBothParticipantsAndEveryTab() {
        String aliceName = name("alice");
        String bobName = name("bob");
        try (TestChatClient alice = TestChatClient.connectAs(port, aliceName);
             TestChatClient bob = TestChatClient.connectAs(port, bobName);
             TestChatClient bobSecondTab = TestChatClient.open(port)) {
            bobSecondTab.send(new ClientFrame.Auth(bob.token()));
            bobSecondTab.await(ServerFrame.Welcome.class);

            alice.send(new ClientFrame.DirectMessage(bobName, "psst", "dm-1"));

            assertThat(alice.await(ServerFrame.Ack.class).ref()).isEqualTo("dm-1");
            ServerFrame.DirectMessage atAlice = alice.await(ServerFrame.DirectMessage.class);
            assertThat(bob.await(ServerFrame.DirectMessage.class)).isEqualTo(atAlice);
            assertThat(bobSecondTab.await(ServerFrame.DirectMessage.class)).isEqualTo(atAlice);
            assertThat(atAlice.from()).isEqualTo(aliceName);
            assertThat(atAlice.to()).isEqualTo(bobName);

            bob.send(new ClientFrame.Typing(null, aliceName));
            assertThat(alice.await(ServerFrame.Typing.class).to()).isEqualTo(aliceName);

            assertThat(messages.directHistory(bobName, aliceName, null, null)).containsExactly(atAlice);
        }
    }

    @Test
    void closingOneTabKeepsTheUserOnline() {
        String user = name("tabs");
        try (TestChatClient first = TestChatClient.connectAs(port, user)) {
            TestChatClient second = TestChatClient.open(port);
            second.send(new ClientFrame.Auth(first.token()));
            second.await(ServerFrame.Welcome.class);

            second.close();
            assertThat(presence.isOnline(user)).isTrue();
        }
        Await.until(user + " is offline", () -> !presence.isOnline(user));
    }

    @Test
    void directMessagesToOfflineUsersOrYourselfAreRejected() {
        String aliceName = name("alice");
        try (TestChatClient alice = TestChatClient.connectAs(port, aliceName)) {
            alice.send(new ClientFrame.DirectMessage(name("nobody"), "hello?", "dm-x"));
            ServerFrame.Error offline = alice.await(ServerFrame.Error.class);
            assertThat(offline.code()).isEqualTo(ErrorCode.USER_OFFLINE);
            assertThat(offline.ref()).isEqualTo("dm-x");

            alice.send(new ClientFrame.DirectMessage(aliceName, "me", "dm-y"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.INVALID);
        }
    }

    @Test
    void invalidRequestsGetErrorFramesAndTheConnectionStaysUsable() {
        String room = newRoom();
        try (TestChatClient alice = TestChatClient.connectAs(port, name("alice"))) {
            alice.send(new ClientFrame.RoomMessage(room, "not joined yet", "r1"));
            ServerFrame.Error notInRoom = alice.await(ServerFrame.Error.class);
            assertThat(notInRoom.code()).isEqualTo(ErrorCode.NOT_IN_ROOM);
            assertThat(notInRoom.ref()).isEqualTo("r1");

            alice.send(new ClientFrame.Join("no-such-room-here"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.NO_SUCH_ROOM);

            alice.send(new ClientFrame.Join("Bad Room!"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.INVALID);

            alice.sendRaw("this is not json");
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.BAD_FRAME);

            alice.sendRaw("{\"type\":\"shout\",\"content\":\"hi\"}");
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.BAD_FRAME);

            alice.sendRaw("null");
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.BAD_FRAME);

            alice.join(room);
            alice.send(new ClientFrame.RoomMessage(room, "x".repeat(2001), "r2"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.INVALID);

            alice.send(new ClientFrame.RoomMessage(room, "   ", "r3"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.INVALID);

            alice.send(new ClientFrame.Typing(room, "someone"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.INVALID);

            alice.send(new ClientFrame.Auth("again"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.INVALID);

            alice.send(new ClientFrame.Ping());
            alice.await(ServerFrame.Pong.class);
            alice.send(new ClientFrame.RoomMessage(room, "still works", "r4"));
            assertThat(alice.await(ServerFrame.Ack.class).ref()).isEqualTo("r4");
            assertThat(alice.isClosed()).isFalse();
        }
    }

    @Test
    void framesBeforeAuthenticationAreRefused() {
        try (TestChatClient anonymous = TestChatClient.open(port)) {
            anonymous.send(new ClientFrame.Join("general"));
            assertThat(anonymous.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.UNAUTHORISED);
        }
    }

    @Test
    void anInvalidTokenClosesTheConnectionWith4401() {
        try (TestChatClient anonymous = TestChatClient.open(port)) {
            anonymous.send(new ClientFrame.Auth("v1.forged.123.sig"));
            assertThat(anonymous.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.UNAUTHORISED);
            assertThat(anonymous.awaitClose()).isEqualTo(CloseCodes.INVALID_TOKEN);
        }
    }

    @Test
    void crossOriginBrowsersAreRejectedButSameOriginIsAllowed() {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws");

        assertThatThrownBy(() -> http.newWebSocketBuilder()
                .header("Origin", "http://evil.example")
                .buildAsync(uri, new WebSocket.Listener() { })
                .orTimeout(5, TimeUnit.SECONDS)
                .join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        e -> assertThat(e.getResponse().statusCode()).isEqualTo(403));

        WebSocket sameOrigin = http.newWebSocketBuilder()
                .header("Origin", "http://localhost:" + port)
                .buildAsync(uri, new WebSocket.Listener() { })
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
        sameOrigin.abort();
    }

    private String newRoom() {
        return rooms.create("tester", "e2e-" + SEQUENCE.incrementAndGet(), "End to end " + SEQUENCE.get(), "").id();
    }

    private static String name(String base) {
        return base + "-" + SEQUENCE.incrementAndGet();
    }
}
