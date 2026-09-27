package io.github.omith786.chat.server.message;

import io.github.omith786.chat.protocol.ErrorCode;
import io.github.omith786.chat.protocol.ServerFrame;
import io.github.omith786.chat.server.room.RoomService;
import io.github.omith786.chat.server.room.RoomView;
import io.github.omith786.chat.server.web.ChatException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the {@code postgres} profile against a real PostgreSQL 17 server (an embedded binary
 * started for the test), proving that the Flyway migration and the JPA mappings work on
 * PostgreSQL and not only on H2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres")
class PostgresProfileTest {

    private static final EmbeddedPostgres POSTGRES = start();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RoomService rooms;

    @Autowired
    MessageService messages;

    @Test
    void migrationRanOnPostgres() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL 17");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
                .isEqualTo(1);
        assertThat(rooms.list()).extracting(RoomView::id).startsWith("general", "random");
    }

    @Test
    void messagesRoundTripIncludingFullUnicode() {
        rooms.create("pg-user", "pg-room", "Postgres room", "");
        String content = "café 😀 " + "x".repeat(1990);

        ServerFrame.RoomMessage stored = messages.postToRoom("pg-room", "pg-user", content);

        assertThat(messages.roomHistory("pg-room", null, null)).containsExactly(stored);
        assertThat(stored.content()).isEqualTo(content);
        assertThat(stored.sentAt()).isNotNull();
    }

    @Test
    void directConversationQueryWorksOnPostgres() {
        messages.postDirect("pg-ann", "pg-ben", "one");
        ServerFrame.DirectMessage latest = messages.postDirect("pg-ben", "pg-ann", "two");

        assertThat(messages.conversations("pg-ann")).singleElement()
                .satisfies(c -> {
                    assertThat(c.with()).isEqualTo("pg-ben");
                    assertThat(c.last()).isEqualTo(latest);
                });
    }

    @Test
    void duplicateRoomIdsAreRejected() {
        rooms.create("pg-user", "pg-dup", "Dup", "");
        assertThatThrownBy(() -> rooms.create("pg-user", "pg-dup", "Dup", ""))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ROOM_EXISTS));
    }

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }
}
