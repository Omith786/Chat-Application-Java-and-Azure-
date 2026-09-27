package io.github.omith786.chat.server.ws;

import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.CloseCodes;
import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.support.TestChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The guard rails, with limits lowered so they are quick to hit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chat.limits.auth-timeout=500ms",
        "chat.limits.connections-per-user=2",
        "chat.limits.rooms-per-connection=1",
        "chat.rate-limits.messages.capacity=3",
        "chat.rate-limits.messages.refill-period=1h",
        "chat.rate-limits.typing.capacity=1",
        "chat.rate-limits.typing.refill-period=1h"
})
class WebSocketLimitsTest {

    @LocalServerPort
    int port;

    @Test
    void connectionsThatNeverAuthenticateAreClosed() {
        try (TestChatClient silent = TestChatClient.open(port)) {
            assertThat(silent.awaitClose()).isEqualTo(CloseCodes.AUTH_TIMEOUT);
        }
    }

    @Test
    void authenticatedConnectionsOutliveTheAuthTimeout() throws InterruptedException {
        try (TestChatClient alice = TestChatClient.connectAs(port, "timeout-ok")) {
            Thread.sleep(800);
            alice.send(new ClientFrame.Ping());
            alice.await(ServerFrame.Pong.class);
        }
    }

    @Test
    void aUserCannotOpenMoreThanTheConfiguredConnections() {
        try (TestChatClient first = TestChatClient.connectAs(port, "many-tabs");
             TestChatClient second = TestChatClient.open(port);
             TestChatClient third = TestChatClient.open(port)) {
            second.send(new ClientFrame.Auth(first.token()));
            second.await(ServerFrame.Welcome.class);

            third.send(new ClientFrame.Auth(first.token()));
            assertThat(third.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.LIMIT_REACHED);
            assertThat(third.awaitClose()).isEqualTo(CloseCodes.TOO_MANY_CONNECTIONS);
        }
    }

    @Test
    void aConnectionCannotJoinMoreThanTheConfiguredRooms() {
        try (TestChatClient alice = TestChatClient.connectAs(port, "room-hog")) {
            alice.join("general");
            alice.send(new ClientFrame.Join("random"));
            assertThat(alice.await(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.LIMIT_REACHED);
        }
    }

    @Test
    void messagesAreRateLimitedPerUser() {
        try (TestChatClient alice = TestChatClient.connectAs(port, "chatterbox")) {
            alice.join("general");
            for (int i = 1; i <= 3; i++) {
                alice.send(new ClientFrame.RoomMessage("general", "message " + i, "m" + i));
                alice.await(ServerFrame.Ack.class, ack -> true);
            }
            alice.send(new ClientFrame.RoomMessage("general", "one too many", "m4"));
            ServerFrame.Error error = alice.await(ServerFrame.Error.class);
            assertThat(error.code()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(error.ref()).isEqualTo("m4");
        }
    }

    @Test
    void typingOverTheLimitIsDroppedSilently() {
        try (TestChatClient alice = TestChatClient.connectAs(port, "fast-typist");
             TestChatClient bob = TestChatClient.connectAs(port, "watcher")) {
            bob.join("general");
            alice.join("general");
            alice.send(new ClientFrame.Typing("general", null));
            alice.send(new ClientFrame.Typing("general", null));

            long typingFrames = bob.drain(Duration.ofMillis(400)).stream()
                    .filter(ServerFrame.Typing.class::isInstance).count();
            assertThat(typingFrames).isEqualTo(1);
            assertThat(alice.drain(Duration.ofMillis(100))).noneMatch(ServerFrame.Error.class::isInstance);
        }
    }
}
