package io.github.omith786.chat.server.cluster;

import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.config.ChatProperties;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import io.github.omith786.chat.server.ws.ConnectionRegistry;
import io.github.omith786.chat.server.ws.FrameCodec;
import io.github.omith786.chat.server.ws.LocalDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Heartbeat and cluster-event handling, wired by hand around the in-memory broker. */
class PresenceHeartbeatTest {

    private static final String SELF = "srv-self";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final InMemoryMessageBroker broker = new InMemoryMessageBroker();
    private final List<ChatEvent> published = new CopyOnWriteArrayList<>();
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final PresenceRegistry presence = new PresenceRegistry(new ServerInstance(SELF), clock);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private PresenceHeartbeat heartbeat;

    @BeforeEach
    void wire() {
        broker.subscribe(published::add);
        ClusterEventHandler events = new ClusterEventHandler(broker, presence, connections,
                new LocalDelivery(connections, new FrameCodec()), new ServerInstance(SELF));
        ChatProperties properties = new ChatProperties(SELF, null, null, null, null,
                new ChatProperties.Presence(Duration.ofSeconds(60), Duration.ofSeconds(180)), null);
        heartbeat = new PresenceHeartbeat(broker, connections, presence, events, scheduler, clock,
                new ServerInstance(SELF), properties);
    }

    @Test
    void startAsksOthersForSnapshotsAnnouncesItselfAndSchedulesTicks() {
        heartbeat.start();

        assertThat(published).first().isEqualTo(new ChatEvent.SnapshotRequested(SELF));
        assertThat(published).contains(new ChatEvent.PresenceSnapshot(SELF, Map.of()));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(60)));
    }

    @Test
    void stopAnnouncesTheShutdownOnce() {
        heartbeat.start();
        heartbeat.stop();
        heartbeat.stop();

        assertThat(published).filteredOn(ChatEvent.InstanceStopping.class::isInstance).hasSize(1);
        assertThat(heartbeat.isRunning()).isFalse();
    }

    @Test
    void tickExpiresInstancesThatWentSilent() {
        broker.publish(new ChatEvent.UserConnected("srv-other", "bob"));
        assertThat(presence.isOnline("bob")).isTrue();

        now.set(now.get().plus(Duration.ofSeconds(181)));
        heartbeat.tick();

        assertThat(presence.isOnline("bob")).isFalse();
        assertThat(presence.knownInstances()).containsExactly(SELF);
    }

    @Test
    void snapshotRequestsFromOthersAreAnsweredButOurOwnAreNot() {
        broker.publish(new ChatEvent.SnapshotRequested(SELF));
        assertThat(published).filteredOn(ChatEvent.PresenceSnapshot.class::isInstance).isEmpty();

        broker.publish(new ChatEvent.SnapshotRequested("srv-new"));
        assertThat(published).filteredOn(ChatEvent.PresenceSnapshot.class::isInstance).hasSize(1);
    }

    @Test
    void remoteSnapshotsAndShutdownsUpdatePresence() {
        broker.publish(new ChatEvent.PresenceSnapshot("srv-other", Map.of("carol", Set.of("general"))));
        assertThat(presence.membersOf("general")).containsExactly("carol");

        broker.publish(new ChatEvent.InstanceStopping("srv-other"));
        assertThat(presence.isOnline("carol")).isFalse();
    }

    @Test
    void roomCreationEventsAreRelayedAsFrames() {
        // No local connections: must simply not fail.
        broker.publish(new ChatEvent.RoomCreated("srv-other", new ServerFrame.RoomCreated("x", "X", "", "bob")));
        assertThat(published).hasSize(1);
    }
}
