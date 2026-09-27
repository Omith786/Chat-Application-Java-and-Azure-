package io.github.omith786.chat.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolJsonTest {

    private final ObjectMapper mapper = ProtocolJson.newMapper();

    static Stream<ClientFrame> clientFrames() {
        return Stream.of(
                new ClientFrame.Auth("abc.def"),
                new ClientFrame.Join("general"),
                new ClientFrame.Leave("general"),
                new ClientFrame.RoomMessage("general", "hello", "r1"),
                new ClientFrame.DirectMessage("bob", "hi bob", null),
                new ClientFrame.Typing("general", null),
                new ClientFrame.Ping());
    }

    static Stream<ServerFrame> serverFrames() {
        Instant now = Instant.parse("2026-01-02T03:04:05.123Z");
        return Stream.of(
                new ServerFrame.Welcome("alice", List.of("alice", "bob"), "srv-1"),
                new ServerFrame.Joined("general", List.of("alice")),
                new ServerFrame.Left("general"),
                new ServerFrame.RoomMessage(7, "general", "alice", "hello", now),
                new ServerFrame.DirectMessage(8, "alice", "bob", "psst", now),
                new ServerFrame.Presence("bob", false),
                new ServerFrame.Membership("general", "bob", true),
                new ServerFrame.Typing("bob", null, "alice"),
                new ServerFrame.RoomCreated("java", "Java", "", "alice"),
                new ServerFrame.Ack("r1", 7),
                new ServerFrame.Error(null, ErrorCode.INVALID, "nope"),
                new ServerFrame.Pong());
    }

    @ParameterizedTest
    @MethodSource("clientFrames")
    void clientFramesRoundTrip(ClientFrame frame) throws JsonProcessingException {
        String json = mapper.writeValueAsString(frame);
        assertThat(mapper.readValue(json, ClientFrame.class)).isEqualTo(frame);
    }

    @ParameterizedTest
    @MethodSource("serverFrames")
    void serverFramesRoundTrip(ServerFrame frame) throws JsonProcessingException {
        String json = mapper.writeValueAsString(frame);
        assertThat(mapper.readValue(json, ServerFrame.class)).isEqualTo(frame);
    }

    @Test
    void writesTypeDiscriminatorIsoTimestampsAndOmitsNulls() throws JsonProcessingException {
        String json = mapper.writeValueAsString(new ServerFrame.RoomMessage(
                1, "general", "alice", "hi", Instant.parse("2026-01-02T03:04:05Z")));
        assertThat(json)
                .contains("\"type\":\"message\"")
                .contains("\"sentAt\":\"2026-01-02T03:04:05Z\"");

        assertThat(mapper.writeValueAsString(new ClientFrame.Typing("general", null)))
                .isEqualTo("{\"type\":\"typing\",\"room\":\"general\"}");
        assertThat(mapper.writeValueAsString(new ClientFrame.Ping())).isEqualTo("{\"type\":\"ping\"}");
    }

    @Test
    void ignoresUnknownPropertiesForForwardCompatibility() throws JsonProcessingException {
        ClientFrame frame = mapper.readValue(
                "{\"type\":\"join\",\"room\":\"general\",\"future\":true}", ClientFrame.class);
        assertThat(frame).isEqualTo(new ClientFrame.Join("general"));
    }

    @Test
    void rejectsUnknownFrameTypes() {
        assertThatThrownBy(() -> mapper.readValue("{\"type\":\"shout\"}", ClientFrame.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void authFrameDoesNotPrintItsToken() {
        assertThat(new ClientFrame.Auth("secret-token").toString()).doesNotContain("secret-token");
    }
}
