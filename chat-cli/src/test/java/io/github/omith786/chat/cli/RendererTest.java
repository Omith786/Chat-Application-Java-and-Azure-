package io.github.omith786.chat.cli;

import io.github.omith786.chat.protocol.ServerFrame;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendererTest {

    private static final Instant AT = Instant.parse("2026-06-01T12:34:56Z");
    private final Renderer plain = new Renderer("alice", false, ZoneId.of("Europe/London"));

    @Test
    void rendersRoomMessagesWithLocalTime() {
        assertThat(plain.render(new ServerFrame.RoomMessage(1, "general", "bob", "hi", AT)))
                .isEqualTo("[13:34] #general <bob> hi");
    }

    @Test
    void rendersDirectMessagesFromTheReadersPointOfView() {
        assertThat(plain.render(new ServerFrame.DirectMessage(2, "bob", "alice", "psst", AT)))
                .isEqualTo("[13:34] [dm] bob -> you: psst");
        assertThat(plain.render(new ServerFrame.DirectMessage(3, "alice", "bob", "hey", AT)))
                .isEqualTo("[13:34] [dm] you -> bob: hey");
    }

    @Test
    void rendersNoticesAndErrors() {
        assertThat(plain.render(new ServerFrame.Presence("bob", true))).isEqualTo("* bob is online");
        assertThat(plain.render(new ServerFrame.Presence("bob", false))).isEqualTo("* bob went offline");
        assertThat(plain.render(new ServerFrame.Membership("general", "bob", true))).isEqualTo("* bob joined #general");
        assertThat(plain.render(new ServerFrame.Joined("general", List.of("alice", "bob"))))
                .isEqualTo("* You joined #general (alice, bob)");
        assertThat(plain.render(new ServerFrame.Error("r", "invalid", "Nope"))).isEqualTo("! Nope");
        assertThat(plain.render(new ServerFrame.Welcome("alice", List.of("alice"), "srv-1")))
                .isEqualTo("* Signed in as alice on srv-1. Online: alice");
    }

    @Test
    void staysQuietForBookkeepingFrames() {
        assertThat(plain.render(new ServerFrame.Ack("r", 1))).isNull();
        assertThat(plain.render(new ServerFrame.Pong())).isNull();
        assertThat(plain.render(new ServerFrame.Typing("bob", "general", null))).isNull();
        assertThat(plain.render(new ServerFrame.Membership("general", "alice", true)))
                .as("our own joins are confirmed by the joined frame").isNull();
    }

    @Test
    void colourModeAddsAnsiCodesAndPlainModeNever() {
        Renderer colour = new Renderer("alice", true, ZoneId.of("UTC"));
        assertThat(colour.render(new ServerFrame.RoomMessage(1, "general", "bob", "hi", AT))).contains("\u001B[");
        assertThat(plain.render(new ServerFrame.RoomMessage(1, "general", "bob", "hi", AT))).doesNotContain("\u001B[");
    }
}
