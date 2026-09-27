package io.github.omith786.chat.server.cluster.azure;

import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.ChatServerApplication;
import io.github.omith786.chat.server.cluster.MessageBroker;
import io.github.omith786.chat.server.message.MessageService;
import io.github.omith786.chat.server.room.RoomService;
import io.github.omith786.chat.server.support.TestChatClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two complete server instances, each with the Azure Web PubSub broker, sharing one fake hub and
 * one database: the scaled-out App Service topology, in a single JVM. Clients on different
 * instances must see each other's messages, presence and typing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiInstanceClusterTest {

    private final FakeWebPubSubHub hub = new FakeWebPubSubHub();
    private final String database = "jdbc:h2:mem:cluster-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private ConfigurableApplicationContext instanceA;
    private ConfigurableApplicationContext instanceB;
    private int portA;
    private int portB;

    @BeforeAll
    void startCluster() {
        instanceA = start("srv-a");
        instanceB = start("srv-b");
        portA = port(instanceA);
        portB = port(instanceB);
    }

    @AfterAll
    void stopCluster() {
        if (instanceB != null) {
            instanceB.close();
        }
        if (instanceA != null) {
            instanceA.close();
        }
    }

    @Test
    void bothInstancesUseTheAzureBroker() {
        assertThat(instanceA.getBean(MessageBroker.class)).isInstanceOf(AzureWebPubSubBroker.class);
        assertThat(instanceA.getBean(MessageBroker.class).isConnected()).isTrue();
        assertThat(instanceB.getBean(MessageBroker.class).details()).containsEntry("connectionId", "conn-srv-b");
    }

    @Test
    void usersOnDifferentInstancesChatInARoom() {
        String room = instanceA.getBean(RoomService.class).create("tester", "cluster-room", "Cluster room", "").id();
        try (TestChatClient alice = TestChatClient.connectAs(portA, "c-alice");
             TestChatClient bob = TestChatClient.connectAs(portB, "c-bob")) {
            assertThat(alice.await(ServerFrame.Presence.class, p -> p.user().equals("c-bob")).online()).isTrue();

            alice.join(room);
            ServerFrame.Joined bobJoined = bob.join(room);
            assertThat(bobJoined.members()).containsExactly("c-alice", "c-bob");

            alice.send(new ClientFrame.RoomMessage(room, "hello from instance A", "x1"));
            ServerFrame.RoomMessage atBob = bob.await(ServerFrame.RoomMessage.class);
            assertThat(atBob.from()).isEqualTo("c-alice");
            assertThat(atBob.content()).isEqualTo("hello from instance A");
            assertThat(alice.await(ServerFrame.Ack.class).id()).isEqualTo(atBob.id());
            assertThat(alice.await(ServerFrame.RoomMessage.class, m -> m.from().equals("c-alice")).id()).isEqualTo(atBob.id());

            bob.send(new ClientFrame.RoomMessage(room, "hello from instance B", "x2"));
            assertThat(alice.await(ServerFrame.RoomMessage.class, m -> m.from().equals("c-bob")).content())
                    .isEqualTo("hello from instance B");

            // Each instance received its own events back from the hub and dropped them, so every
            // client got each message exactly once.
            assertThat(alice.drain(Duration.ofMillis(300)))
                    .filteredOn(ServerFrame.RoomMessage.class::isInstance).isEmpty();

            // History is shared through the database, whichever instance serves it.
            assertThat(instanceB.getBean(MessageService.class).roomHistory(room, null, null))
                    .extracting(ServerFrame.RoomMessage::content)
                    .containsExactly("hello from instance A", "hello from instance B");
        }
    }

    @Test
    void directMessagesAndTypingCrossInstances() {
        try (TestChatClient alice = TestChatClient.connectAs(portA, "d-alice");
             TestChatClient bob = TestChatClient.connectAs(portB, "d-bob")) {
            alice.await(ServerFrame.Presence.class, p -> p.user().equals("d-bob"));

            bob.send(new ClientFrame.Typing(null, "d-alice"));
            assertThat(alice.await(ServerFrame.Typing.class).from()).isEqualTo("d-bob");

            bob.send(new ClientFrame.DirectMessage("d-alice", "across the cluster", "dm"));
            ServerFrame.DirectMessage atAlice = alice.await(ServerFrame.DirectMessage.class);
            assertThat(atAlice.from()).isEqualTo("d-bob");
            assertThat(bob.await(ServerFrame.DirectMessage.class)).isEqualTo(atAlice);
        }
    }

    @Test
    void tokensAndUsernamesAreClusterWide() throws Exception {
        try (TestChatClient alice = TestChatClient.connectAs(portA, "u-alice")) {
            // A token issued by A is accepted by B, because the instances share the signing secret.
            try (TestChatClient secondTab = TestChatClient.open(portB)) {
                secondTab.send(new ClientFrame.Auth(alice.token()));
                assertThat(secondTab.await(ServerFrame.Welcome.class).instance()).isEqualTo("srv-b");
            }
            // B knows the name is taken, although alice signed in on A.
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + portB + "/api/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"u-alice\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
        }
    }

    @Test
    void roomsCreatedOnOneInstanceAreAnnouncedOnTheOther() {
        try (TestChatClient bob = TestChatClient.connectAs(portB, "r-bob")) {
            instanceA.getBean(RoomService.class).create("r-alice", null, "Announced Room", "");
            assertThat(bob.await(ServerFrame.RoomCreated.class).id()).isEqualTo("announced-room");
        }
    }

    @Test
    void aSilentInstanceExpiresAndComesBackWhenItsHeartbeatsResume() {
        try (TestChatClient alice = TestChatClient.connectAs(portA, "p-alice");
             TestChatClient bob = TestChatClient.connectAs(portB, "p-bob")) {
            alice.await(ServerFrame.Presence.class, p -> p.user().equals("p-bob") && p.online());

            hub.partition("srv-b", true);
            try {
                // chat.presence.expiry is 1s in this test, so A gives up on B quickly.
                alice.await(ServerFrame.Presence.class, p -> p.user().equals("p-bob") && !p.online());
            } finally {
                hub.partition("srv-b", false);
            }
            alice.await(ServerFrame.Presence.class, p -> p.user().equals("p-bob") && p.online());
            assertThat(bob.isClosed()).isFalse();
        }
    }

    @Test
    void aCleanlyStoppedInstanceTakesItsUsersOfflineElsewhere() {
        ConfigurableApplicationContext instanceC = start("srv-c");
        try (TestChatClient alice = TestChatClient.connectAs(portA, "s-alice")) {
            TestChatClient erin = TestChatClient.connectAs(port(instanceC), "s-erin");
            alice.await(ServerFrame.Presence.class, p -> p.user().equals("s-erin") && p.online());

            instanceC.close();
            alice.await(ServerFrame.Presence.class, p -> p.user().equals("s-erin") && !p.online());
            erin.close();
        } finally {
            instanceC.close();
        }
    }

    private ConfigurableApplicationContext start(String id) {
        return new SpringApplicationBuilder(ChatServerApplication.class)
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("webPubSubServiceClient", hub.serviceClient(id));
                    context.getBeanFactory().registerSingleton("webPubSubSubscriber", hub.subscriber(id));
                })
                .run("--server.port=0",
                        "--chat.instance-id=" + id,
                        "--chat.broker.type=azure-web-pubsub",
                        "--spring.datasource.url=" + database,
                        "--chat.presence.snapshot-interval=200ms",
                        "--chat.presence.expiry=1s",
                        "--spring.lifecycle.timeout-per-shutdown-phase=2s");
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }
}
