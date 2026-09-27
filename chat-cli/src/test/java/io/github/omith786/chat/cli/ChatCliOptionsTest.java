package io.github.omith786.chat.cli;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChatCliOptionsTest {

    @Test
    void defaultsToLocalhostAndTheGeneralRoom() {
        ChatCli.Options options = ChatCli.Options.parse(new String[0], Map.of());
        assertThat(options.server()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(options.room()).isEqualTo("general");
        assertThat(options.user()).isNull();
    }

    @Test
    void readsTheServerFromTheEnvironmentAndFlagsOverrideIt() {
        assertThat(ChatCli.Options.parse(new String[0], Map.of("CHAT_SERVER", "https://chat.example")).server())
                .isEqualTo(URI.create("https://chat.example"));
        ChatCli.Options options = ChatCli.Options.parse(
                new String[]{"-s", "http://other:9000", "--user", "alice", "--room", "none", "--no-color"},
                Map.of("CHAT_SERVER", "https://chat.example"));
        assertThat(options.server()).isEqualTo(URI.create("http://other:9000"));
        assertThat(options.user()).isEqualTo("alice");
        assertThat(options.room()).isNull();
        assertThat(options.colour()).isFalse();
    }

    @Test
    void noColorEnvironmentVariableDisablesColour() {
        assertThat(ChatCli.Options.parse(new String[0], Map.of("NO_COLOR", "1")).colour()).isFalse();
    }

    @Test
    void rejectsBadArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatCli.Options.parse(new String[]{"--bogus"}, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatCli.Options.parse(new String[]{"--user"}, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatCli.Options.parse(new String[]{"-s", "ftp://x"}, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatCli.Options.parse(new String[]{"-r", "Bad Room"}, Map.of()));
    }

    @Test
    void webSocketUriFollowsTheServerScheme() {
        assertThat(new ChatApi(null, URI.create("http://localhost:8080")).webSocketUri())
                .isEqualTo(URI.create("ws://localhost:8080/ws"));
        assertThat(new ChatApi(null, URI.create("https://chat.example/app/")).webSocketUri())
                .isEqualTo(URI.create("wss://chat.example/app/ws"));
    }
}
