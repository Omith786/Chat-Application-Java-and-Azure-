package io.github.omith786.chat.server.ws;

import io.github.omith786.chat.server.ws.ConnectionRegistry.AuthResult;
import io.github.omith786.chat.server.ws.ConnectionRegistry.JoinResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionRegistryTest {

    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final AtomicInteger ids = new AtomicInteger();

    @Test
    void reportsFirstAndAdditionalConnectionsAndEnforcesTheLimit() {
        ClientConnection one = connection();
        ClientConnection two = connection();
        ClientConnection three = connection();

        assertThat(registry.authenticate(one, "alice", 2)).isEqualTo(AuthResult.FIRST_CONNECTION);
        assertThat(registry.authenticate(two, "alice", 2)).isEqualTo(AuthResult.ADDITIONAL_CONNECTION);
        assertThat(registry.authenticate(three, "alice", 2)).isEqualTo(AuthResult.TOO_MANY_CONNECTIONS);
        assertThat(three.isAuthenticated()).isFalse();
        assertThat(registry.ofUser("alice")).containsExactly(one, two);
    }

    @Test
    void joinReportsTheUsersFirstConnectionInARoom() {
        ClientConnection one = authenticated("alice");
        ClientConnection two = authenticated("alice");

        assertThat(registry.join(one, "general", 10)).isEqualTo(JoinResult.FIRST_FOR_USER);
        assertThat(registry.join(one, "general", 10)).isEqualTo(JoinResult.ALREADY_JOINED);
        assertThat(registry.join(two, "general", 10)).isEqualTo(JoinResult.JOINED);
        assertThat(registry.inRoom("general")).containsExactly(one, two);
    }

    @Test
    void joinEnforcesThePerConnectionRoomLimit() {
        ClientConnection one = authenticated("alice");
        registry.join(one, "a", 2);
        registry.join(one, "b", 2);
        assertThat(registry.join(one, "c", 2)).isEqualTo(JoinResult.TOO_MANY_ROOMS);
        assertThat(one.rooms()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void leaveReportsWhenTheUsersLastConnectionLeaves() {
        ClientConnection one = authenticated("alice");
        ClientConnection two = authenticated("alice");
        registry.join(one, "general", 10);
        registry.join(two, "general", 10);

        assertThat(registry.leave(one, "general")).isFalse();
        assertThat(registry.leave(one, "general")).as("not joined any more").isFalse();
        assertThat(registry.leave(two, "general")).isTrue();
        assertThat(registry.inRoom("general")).isEmpty();
        assertThat(registry.roomsIndexed()).doesNotContain("general");
    }

    @Test
    void removingTheLastConnectionReportsRoomsLeftAndDisconnect() {
        ClientConnection one = authenticated("alice");
        ClientConnection two = authenticated("alice");
        registry.join(one, "general", 10);
        registry.join(one, "random", 10);
        registry.join(two, "general", 10);

        ConnectionRegistry.Removal first = registry.remove(one);
        assertThat(first.user()).isEqualTo("alice");
        assertThat(first.lastConnection()).isFalse();
        assertThat(first.roomsLeft()).containsExactly("random");

        ConnectionRegistry.Removal second = registry.remove(two);
        assertThat(second.lastConnection()).isTrue();
        assertThat(second.roomsLeft()).containsExactly("general");
        assertThat(registry.authenticated()).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void removingIsIdempotentAndHandlesUnauthenticatedConnections() {
        ClientConnection anonymous = connection();
        registry.add(anonymous);
        assertThat(registry.remove(anonymous).user()).isNull();
        assertThat(registry.remove(anonymous)).isEqualTo(new ConnectionRegistry.Removal(null, false, List.of()));
    }

    @Test
    void snapshotListsUsersWithTheUnionOfTheirRooms() {
        ClientConnection one = authenticated("alice");
        ClientConnection two = authenticated("alice");
        authenticated("bob");
        registry.join(one, "general", 10);
        registry.join(two, "random", 10);

        assertThat(registry.snapshot()).isEqualTo(Map.of(
                "alice", Set.of("general", "random"),
                "bob", Set.of()));
    }

    private ClientConnection authenticated(String user) {
        ClientConnection connection = connection();
        registry.add(connection);
        registry.authenticate(connection, user, 10);
        return connection;
    }

    private ClientConnection connection() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s" + ids.incrementAndGet());
        when(session.isOpen()).thenReturn(true);
        return new ClientConnection(session);
    }
}
