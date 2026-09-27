package io.github.omith786.chat.cli;

import io.github.omith786.chat.protocol.ChatRules;
import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.CloseCodes;
import io.github.omith786.chat.protocol.ServerFrame;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The interactive terminal session: executes {@link Command}s typed by the user and prints what
 * the server pushes. Output from the WebSocket thread and from commands is serialised through
 * one lock so lines never interleave mid-line.
 *
 * <p>If the connection drops unexpectedly the shell reconnects with exponential back-off, reusing
 * the session token, and rejoins the rooms it was in.
 */
public final class ChatShell implements AutoCloseable {

    static final int JOIN_HISTORY = 10;
    private static final int MAX_RECONNECT_ATTEMPTS = 6;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final ChatApi api;
    private final HttpClient http;
    private final PrintStream out;
    private final boolean colour;
    private final ZoneId zone;
    private final Set<String> joinedRooms = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicLong refs = new AtomicLong();

    private volatile ChatConnection connection;
    private volatile Renderer renderer;
    private volatile String username;
    private volatile Target target;
    private volatile boolean closing;

    /** Where plain text goes: a room, or a user for direct messages. */
    private record Target(String room, String user) {
        @Override
        public String toString() {
            return room != null ? "#" + room : "@" + user;
        }
    }

    public ChatShell(ChatApi api, HttpClient http, PrintStream out, boolean colour, ZoneId zone) {
        this.api = api;
        this.http = http;
        this.out = out;
        this.colour = colour;
        this.zone = zone;
    }

    /** Creates a session for {@code requestedName} and connects. */
    public void signIn(String requestedName) {
        ChatApi.Session session = api.createSession(requestedName);
        username = session.username();
        renderer = new Renderer(username, colour, zone);
        connect();
    }

    /** The signed-in username. */
    public String username() {
        return username;
    }

    /** Where plain text currently goes, e.g. {@code #general}, or {@code null}. */
    public String currentTarget() {
        Target current = target;
        return current == null ? null : current.toString();
    }

    /** Rooms joined on the current connection. */
    public Set<String> joinedRooms() {
        synchronized (joinedRooms) {
            return Set.copyOf(joinedRooms);
        }
    }

    /** Reads commands until {@code /quit} or end of input. */
    public void run(BufferedReader in) throws IOException {
        print(renderer.notice("Type /help for commands."));
        String line;
        while ((line = in.readLine()) != null) {
            if (!execute(line)) {
                return;
            }
        }
    }

    /**
     * Executes one line of input.
     *
     * @return {@code false} if the user asked to quit
     */
    public boolean execute(String line) {
        Command command = CommandParser.parse(line);
        try {
            return execute(command);
        } catch (ChatApiException e) {
            print(renderer.error(e.getMessage()));
            return true;
        }
    }

    private boolean execute(Command command) {
        switch (command) {
            case Command.Say say -> say(say.text());
            case Command.Join join -> join(join.room());
            case Command.Leave leave -> leave(leave.room());
            case Command.Switch sw -> switchTo(sw.target());
            case Command.Direct dm -> sendDirect(dm.user(), dm.text());
            case Command.Rooms rooms -> listRooms();
            case Command.Create create -> {
                ChatApi.Room room = api.createRoom(create.name(), "");
                print(renderer.notice("Created #" + room.id() + ". Use /join " + room.id() + " to enter it."));
            }
            case Command.Who who -> {
                List<String> users = who.room() == null ? api.onlineUsers() : api.roomMembers(who.room());
                String where = who.room() == null ? "Online" : "In #" + who.room();
                print(renderer.notice(where + " (" + users.size() + "): " + String.join(", ", users)));
            }
            case Command.History history -> showHistory(history.count());
            case Command.Help help -> print(CommandParser.help());
            case Command.Quit quit -> {
                return false;
            }
            case Command.Empty empty -> {
                // nothing to do
            }
            case Command.Invalid invalid -> print(renderer.error(invalid.message()));
        }
        return true;
    }

    private void say(String text) {
        Target current = target;
        if (current == null) {
            print(renderer.error("You are not in a room yet: try /join general"));
            return;
        }
        if (!validContent(text)) {
            return;
        }
        String ref = nextRef();
        if (current.room() != null) {
            requireConnection().send(new ClientFrame.RoomMessage(current.room(), text, ref));
        } else {
            requireConnection().send(new ClientFrame.DirectMessage(current.user(), text, ref));
        }
    }

    /** Joins a room: fetch its history first (which also proves it exists), then subscribe. */
    private void join(String room) {
        List<ServerFrame.RoomMessage> history = api.roomHistory(room, JOIN_HISTORY);
        requireConnection().send(new ClientFrame.Join(room));
        joinedRooms.add(room);
        target = new Target(room, null);
        if (!history.isEmpty()) {
            print(renderer.notice("Recent messages in #" + room + ":"));
            history.forEach(m -> print(renderer.roomMessage(m)));
        }
    }

    private void leave(String requested) {
        Target current = target;
        String room = requested != null ? requested : current != null ? current.room() : null;
        if (room == null) {
            print(renderer.error("Usage: /leave <room>"));
            return;
        }
        requireConnection().send(new ClientFrame.Leave(room));
        joinedRooms.remove(room);
        if (current != null && room.equals(current.room())) {
            target = null;
        }
    }

    private void switchTo(String destination) {
        if (destination.startsWith("@")) {
            String user = destination.substring(1);
            if (!ChatRules.isValidUsername(user.toLowerCase(Locale.ROOT))) {
                print(renderer.error("'" + user + "' is not a valid username"));
                return;
            }
            target = new Target(null, user.toLowerCase(Locale.ROOT));
            print(renderer.notice("Now messaging @" + target.user() + " directly"));
        } else if (joinedRooms.contains(destination)) {
            target = new Target(destination, null);
            print(renderer.notice("Now talking in #" + destination));
        } else {
            print(renderer.error("You have not joined #" + destination + ": use /join " + destination));
        }
    }

    private void sendDirect(String user, String text) {
        if (validContent(text)) {
            requireConnection().send(new ClientFrame.DirectMessage(user.toLowerCase(Locale.ROOT), text, nextRef()));
        }
    }

    private void listRooms() {
        List<ChatApi.Room> rooms = api.rooms();
        for (ChatApi.Room room : rooms) {
            String mark = joinedRooms.contains(room.id()) ? "*" : " ";
            String description = room.description() == null || room.description().isEmpty()
                    ? "" : " - " + room.description();
            print(mark + " #" + room.id() + "  " + room.name() + " (" + room.online() + " online)" + description);
        }
    }

    private void showHistory(int count) {
        Target current = target;
        if (current == null) {
            print(renderer.error("Join a room or /switch @user first"));
        } else if (current.room() != null) {
            api.roomHistory(current.room(), count).forEach(m -> print(renderer.roomMessage(m)));
        } else {
            api.directHistory(current.user(), count).forEach(m -> print(renderer.directMessage(m)));
        }
    }

    private boolean validContent(String text) {
        try {
            ChatRules.normaliseContent(text);
            return true;
        } catch (IllegalArgumentException e) {
            print(renderer.error(e.getMessage()));
            return false;
        }
    }

    private void connect() {
        connection = ChatConnection.connect(http, api.webSocketUri(), api.token(), this::onFrame, this::onClose);
        print(renderer.render(connection.welcome()));
    }

    private void onFrame(ServerFrame frame) {
        if (frame instanceof ServerFrame.RoomCreated created && created.createdBy().equals(username)) {
            return; // already announced by /create
        }
        String line = renderer.render(frame);
        if (line != null) {
            print(line);
        }
    }

    private void onClose(int code, String reason) {
        if (closing) {
            return;
        }
        if (code == CloseCodes.INVALID_TOKEN) {
            print(renderer.error("Your session has expired. Restart the client to sign in again."));
            return;
        }
        print(renderer.error("Disconnected (" + code + (reason == null || reason.isEmpty() ? "" : " " + reason)
                + "), reconnecting..."));
        Thread reconnect = new Thread(this::reconnectWithBackoff, "chat-reconnect");
        reconnect.setDaemon(true);
        reconnect.start();
    }

    private void reconnectWithBackoff() {
        Duration delay = Duration.ofSeconds(1);
        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS && !closing; attempt++) {
            try {
                Thread.sleep(delay.toMillis());
                connect();
                for (String room : joinedRooms()) {
                    connection.send(new ClientFrame.Join(room));
                }
                print(renderer.notice("Reconnected"));
                return;
            } catch (ChatApiException e) {
                if ("unauthorised".equals(e.code())) {
                    print(renderer.error("Your session is no longer valid. Restart the client to sign in again."));
                    return;
                }
                delay = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!closing) {
            print(renderer.error("Could not reconnect. Press Ctrl+D or type /quit to exit."));
        }
    }

    private ChatConnection requireConnection() {
        ChatConnection current = connection;
        if (current == null || !current.isOpen()) {
            throw new ChatApiException(0, null, "Not connected");
        }
        return current;
    }

    private String nextRef() {
        return "c" + refs.incrementAndGet();
    }

    private void print(String line) {
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }

    /** Disconnects without triggering a reconnect. */
    @Override
    public void close() {
        closing = true;
        ChatConnection current = connection;
        if (current != null) {
            current.close();
        }
    }
}
