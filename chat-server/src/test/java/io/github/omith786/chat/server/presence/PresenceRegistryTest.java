package io.github.omith786.chat.server.presence;

import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.presence.PresenceDelta.RoomJoined;
import io.github.omith786.chat.server.presence.PresenceDelta.RoomLeft;
import io.github.omith786.chat.server.presence.PresenceDelta.UserOffline;
import io.github.omith786.chat.server.presence.PresenceDelta.UserOnline;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceRegistryTest {

    private static final String LOCAL = "local";
    private static final String A = "a";
    private static final String B = "b";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final PresenceRegistry registry = new PresenceRegistry(new ServerInstance(LOCAL), new Clock() {
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
    });

    @Test
    void firstConnectionAnywhereBringsAUserOnline() {
        assertThat(registry.userConnected(A, "alice")).containsExactly(new UserOnline("alice"));
        assertThat(registry.userConnected(B, "alice")).isEmpty();
        assertThat(registry.isOnline("alice")).isTrue();
        assertThat(registry.onlineUsers()).containsExactly("alice");
    }

    @Test
    void userStaysOnlineUntilTheirLastInstanceDisconnects() {
        registry.userConnected(A, "alice");
        registry.userConnected(B, "alice");

        assertThat(registry.userDisconnected(A, "alice")).isEmpty();
        assertThat(registry.isOnline("alice")).isTrue();
        assertThat(registry.userDisconnected(B, "alice")).containsExactly(new UserOffline("alice"));
        assertThat(registry.isOnline("alice")).isFalse();
    }

    @Test
    void roomMembershipIsTheUnionAcrossInstances() {
        registry.userConnected(A, "alice");
        assertThat(registry.roomJoined(A, "general", "alice")).containsExactly(new RoomJoined("general", "alice"));
        assertThat(registry.roomJoined(B, "general", "alice"))
                .as("already in the room via A").isEmpty();
        registry.roomJoined(B, "general", "bob");

        assertThat(registry.membersOf("general")).containsExactly("alice", "bob");
        assertThat(registry.memberCounts()).containsEntry("general", 2);

        assertThat(registry.roomLeft(A, "general", "alice")).isEmpty();
        assertThat(registry.roomLeft(B, "general", "alice")).containsExactly(new RoomLeft("general", "alice"));
        assertThat(registry.membersOf("general")).containsExactly("bob");
    }

    @Test
    void joiningARoomImpliesBeingOnline() {
        assertThat(registry.roomJoined(A, "general", "alice"))
                .containsExactly(new UserOnline("alice"), new RoomJoined("general", "alice"));
    }

    @Test
    void goingOfflineLeavesRoomsFirst() {
        registry.roomJoined(A, "general", "alice");
        registry.roomJoined(A, "random", "alice");

        assertThat(registry.userDisconnected(A, "alice")).containsExactly(
                new RoomLeft("general", "alice"), new RoomLeft("random", "alice"), new UserOffline("alice"));
    }

    @Test
    void snapshotReplacesAnInstanceViewAndReportsTheDifference() {
        registry.roomJoined(A, "general", "alice");
        registry.userConnected(A, "carol");

        var deltas = registry.replace(A, Map.of(
                "alice", Set.of("random"),
                "bob", Set.of()));

        assertThat(deltas).containsExactly(
                new RoomLeft("general", "alice"),
                new RoomJoined("random", "alice"),
                new UserOnline("bob"),
                new UserOffline("carol"));
        assertThat(registry.onlineUsers()).containsExactly("alice", "bob");
    }

    @Test
    void identicalSnapshotsProduceNoDeltas() {
        registry.replace(A, Map.of("alice", Set.of("general")));
        assertThat(registry.replace(A, Map.of("alice", Set.of("general")))).isEmpty();
    }

    @Test
    void silentInstancesExpireButTheLocalOneNeverDoes() {
        registry.userConnected(LOCAL, "me");
        registry.roomJoined(A, "general", "alice");
        now.set(now.get().plusSeconds(60));
        registry.userConnected(B, "bob");

        var deltas = registry.expireSilentInstances(now.get().minusSeconds(30));

        assertThat(deltas).containsExactly(new RoomLeft("general", "alice"), new UserOffline("alice"));
        assertThat(registry.onlineUsers()).containsExactly("bob", "me");
        assertThat(registry.knownInstances()).containsExactlyInAnyOrder(LOCAL, B);
    }

    @Test
    void anyEventFromAnInstanceCountsAsAHeartbeat() {
        registry.userConnected(A, "alice");
        now.set(now.get().plusSeconds(60));
        registry.replace(A, Map.of("alice", Set.of()));

        assertThat(registry.expireSilentInstances(now.get().minusSeconds(30))).isEmpty();
        assertThat(registry.isOnline("alice")).isTrue();
    }

    @Test
    void removingAStoppedInstanceDropsOnlyUsersHeldThere() {
        registry.userConnected(A, "alice");
        registry.userConnected(A, "bob");
        registry.userConnected(B, "bob");

        assertThat(registry.removeInstance(A)).containsExactly(new UserOffline("alice"));
        assertThat(registry.onlineUsers()).containsExactly("bob");
        assertThat(registry.removeInstance("unknown")).isEmpty();
    }

    @Test
    void theLocalInstanceCannotBeRemoved() {
        registry.userConnected(LOCAL, "me");
        assertThat(registry.removeInstance(LOCAL)).isEmpty();
        assertThat(registry.isOnline("me")).isTrue();
    }

    @Test
    void leavingARoomYouWereNotInChangesNothing() {
        assertThat(registry.roomLeft(A, "general", "ghost")).isEmpty();
        assertThat(registry.userDisconnected(A, "ghost")).isEmpty();
    }
}
