package io.github.omith786.chat.cli;

import io.github.omith786.chat.protocol.ChatRules;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

/**
 * Command-line entry point for the terminal chat client.
 *
 * <pre>
 * java -jar chat-cli-all.jar --server http://localhost:8080 --user alice --room general
 * </pre>
 */
public final class ChatCli {

    private ChatCli() {
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(Options.USAGE);
            return;
        }

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String user = options.user();
        if (user == null) {
            out.print("Username: ");
            out.flush();
            user = in.readLine();
            if (user == null) {
                return;
            }
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ChatApi api = new ChatApi(http, options.server());
        try (ChatShell shell = new ChatShell(api, http, out, options.colour(), ZoneId.systemDefault())) {
            try {
                shell.signIn(user);
            } catch (ChatApiException e) {
                System.err.println("Could not sign in: " + e.getMessage());
                System.exit(1);
                return;
            }
            if (options.room() != null) {
                shell.execute("/join " + options.room());
            }
            shell.run(in);
        }
    }

    /**
     * Parsed command-line options.
     *
     * @param room room to join on start, or {@code null} for none
     */
    record Options(URI server, String user, String room, boolean colour, boolean help) {

        static final String USAGE = """
                Usage: chat-cli [--server URL] [--user NAME] [--room ROOM|none] [--no-color]
                  --server, -s   chat server URL (default: $CHAT_SERVER or http://localhost:8080)
                  --user, -u     username (asked for if omitted)
                  --room, -r     room to join on start (default: general; 'none' to skip)
                  --no-color     plain output without ANSI colours (also honours NO_COLOR)
                """;

        static Options parse(String[] args, Map<String, String> env) {
            String server = env.getOrDefault("CHAT_SERVER", "http://localhost:8080");
            String user = null;
            String room = "general";
            boolean colour = System.console() != null && !env.containsKey("NO_COLOR")
                    && !"dumb".equals(env.get("TERM"));
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--server", "-s" -> server = value(args, ++i, "--server");
                    case "--user", "-u" -> user = value(args, ++i, "--user");
                    case "--room", "-r" -> room = value(args, ++i, "--room");
                    case "--no-color", "--no-colour" -> colour = false;
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if ("none".equals(room)) {
                room = null;
            } else if (room != null && !ChatRules.isValidRoomId(room)) {
                throw new IllegalArgumentException("Not a valid room id: " + room);
            }
            URI uri = URI.create(server);
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                throw new IllegalArgumentException("--server must be an http:// or https:// URL");
            }
            return new Options(uri, user, room, colour, help);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }
    }
}
