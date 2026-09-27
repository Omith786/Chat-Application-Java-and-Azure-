package io.github.omith786.chat.server.cli;

import io.github.omith786.chat.cli.ChatApi;
import io.github.omith786.chat.cli.ChatApiException;
import io.github.omith786.chat.cli.ChatShell;
import io.github.omith786.chat.server.support.Await;
import io.github.omith786.chat.server.ws.ClientConnection;
import io.github.omith786.chat.server.ws.ConnectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real terminal client ({@code chat-cli}'s {@link ChatShell}) against a running
 * server, so the Java client on the other end of the wire is tested too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalClientEndToEndTest {

    @LocalServerPort
    int port;

    @Autowired
    ConnectionRegistry connections;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<ChatShell> shells = new ArrayList<>();

    @AfterEach
    void closeShells() {
        shells.forEach(ChatShell::close);
    }

    @Test
    void twoTerminalUsersChatInARoom() {
        Terminal alice = signIn("term-alice");
        Terminal bob = signIn("term-bob");

        alice.run("/create Terminal Room");
        alice.awaitOutput("Created #terminal-room");
        alice.run("/join terminal-room");
        bob.run("/join terminal-room");
        alice.awaitOutput("term-bob joined #terminal-room");
        assertThat(bob.shell.currentTarget()).isEqualTo("#terminal-room");

        alice.run("Hello from the terminal");
        bob.awaitOutput("#terminal-room <term-alice> Hello from the terminal");
        alice.awaitOutput("<term-alice> Hello from the terminal");

        bob.run("/history 5");
        bob.awaitOutputCount("<term-alice> Hello from the terminal", 2);

        bob.run("/who terminal-room");
        bob.awaitOutput("In #terminal-room (2): term-alice, term-bob");

        bob.run("/leave");
        bob.awaitOutput("You left #terminal-room");
        assertThat(bob.shell.currentTarget()).isNull();
        assertThat(bob.shell.joinedRooms()).doesNotContain("terminal-room");
    }

    @Test
    void directMessagesAndSwitchingTargets() {
        Terminal alice = signIn("term-ann");
        Terminal bob = signIn("term-ben");

        alice.run("/dm term-ben are you there?");
        bob.awaitOutput("[dm] term-ann -> you: are you there?");
        alice.awaitOutput("[dm] you -> term-ben: are you there?");

        bob.run("/switch @term-ann");
        bob.run("yes, here");
        alice.awaitOutput("[dm] term-ben -> you: yes, here");

        bob.run("/history");
        bob.awaitOutputCount("yes, here", 2); // the live echo, then the history line
    }

    @Test
    void userErrorsArePrintedNotThrown() {
        Terminal alice = signIn("term-err");

        alice.run("talking to nobody");
        alice.awaitOutput("! You are not in a room yet");
        alice.run("/join no-such-room");
        alice.awaitOutput("! No room called 'no-such-room'");
        alice.run("/dm term-offline hello");
        alice.awaitOutput("! term-offline is not online");
        alice.run("/switch random");
        alice.awaitOutput("! You have not joined #random");
        alice.run("/unknown");
        alice.awaitOutput("! Unknown command /unknown");
        assertThat(alice.shell.execute("/quit")).isFalse();
    }

    @Test
    void signingInWithANameThatIsOnlineFails() {
        signIn("term-dupe");
        ChatShell shell = new ChatShell(new ChatApi(http, base()), http, new PrintStream(new ByteArrayOutputStream()),
                false, ZoneId.of("UTC"));
        assertThatThrownBy(() -> shell.signIn("TERM-DUPE"))
                .isInstanceOfSatisfying(ChatApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("username_taken");
                });
    }

    @Test
    void theClientReconnectsAndRejoinsAfterTheServerDropsIt() {
        Terminal alice = signIn("term-drop");
        Terminal bob = signIn("term-stay");
        alice.run("/join general");
        bob.run("/join general");
        alice.awaitOutput("term-stay joined #general");

        for (ClientConnection connection : connections.ofUser("term-drop")) {
            connection.close(CloseStatus.SERVICE_RESTARTED);
        }
        alice.awaitOutput("Disconnected (1012");
        alice.awaitOutput("Reconnected");
        Await.until("term-drop rejoined general", () -> connections.inRoom("general").stream()
                .anyMatch(c -> "term-drop".equals(c.user())));

        bob.run("welcome back");
        alice.awaitOutput("<term-stay> welcome back");
    }

    @Test
    void theInteractiveLoopStopsAtQuit() throws Exception {
        Terminal alice = signIn("term-loop");
        alice.shell.run(new BufferedReader(new StringReader("/help\n/rooms\n/quit\nnever sent\n")));
        alice.awaitOutput("/join <room>");
        alice.awaitOutput("#general  General");
        assertThat(alice.output()).doesNotContain("never sent");
    }

    private Terminal signIn(String username) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        ChatShell shell = new ChatShell(new ChatApi(http, base()), http, out, false, ZoneId.of("UTC"));
        shells.add(shell);
        shell.signIn(username);
        return new Terminal(shell, bytes);
    }

    private URI base() {
        return URI.create("http://localhost:" + port);
    }

    private record Terminal(ChatShell shell, ByteArrayOutputStream bytes) {

        void run(String line) {
            shell.execute(line);
        }

        String output() {
            synchronized (bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }

        void awaitOutput(String text) {
            awaitOutputCount(text, 1);
        }

        void awaitOutputCount(String text, int count) {
            try {
                Await.until("output contains \"" + text + "\" " + count + "x",
                        () -> occurrences(output(), text) >= count);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "; output was:\n" + output(), e);
            }
        }

        private static int occurrences(String haystack, String needle) {
            int count = 0;
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
                count++;
            }
            return count;
        }
    }
}
