package io.github.omith786.chat.cli;

import java.util.Locale;

/** Turns a line of user input into a {@link Command}. Pure, so it is easy to test. */
public final class CommandParser {

    static final int DEFAULT_HISTORY = 20;
    static final int MAX_HISTORY = 100;

    private CommandParser() {
    }

    /** Parses one line. Lines starting with {@code //} send a literal message beginning with {@code /}. */
    public static Command parse(String line) {
        if (line == null || line.isBlank()) {
            return new Command.Empty();
        }
        String trimmed = line.strip();
        if (trimmed.startsWith("//")) {
            return new Command.Say(trimmed.substring(1));
        }
        if (!trimmed.startsWith("/")) {
            return new Command.Say(trimmed);
        }
        String[] parts = trimmed.substring(1).split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].strip() : "";
        return switch (name) {
            case "join", "j" -> rest.isEmpty() ? usage("/join <room>") : new Command.Join(firstWord(rest));
            case "leave", "part" -> new Command.Leave(rest.isEmpty() ? null : firstWord(rest));
            case "switch", "s" -> rest.isEmpty() ? usage("/switch <room> or /switch @user") : new Command.Switch(firstWord(rest));
            case "dm", "msg" -> direct(rest);
            case "rooms" -> new Command.Rooms();
            case "create" -> rest.isEmpty() ? usage("/create <room name>") : new Command.Create(rest);
            case "who" -> new Command.Who(rest.isEmpty() ? null : firstWord(rest));
            case "history", "h" -> history(rest);
            case "help", "?" -> new Command.Help();
            case "quit", "exit", "q" -> new Command.Quit();
            default -> new Command.Invalid("Unknown command /" + name + " (try /help)");
        };
    }

    /** The text printed by {@code /help}. */
    public static String help() {
        return String.join(System.lineSeparator(),
                "Commands:",
                "  <text>              send to the current room or conversation",
                "  /join <room>        join a room and switch to it",
                "  /leave [room]       leave a room (default: the current one)",
                "  /switch <room>      send plain text to a room you have joined",
                "  /switch @<user>     send plain text to a user as direct messages",
                "  /dm <user> <text>   send one direct message",
                "  /rooms              list rooms",
                "  /create <name>      create a room",
                "  /who [room]         who is online, or who is in a room",
                "  /history [n]        show the last n messages here (default " + DEFAULT_HISTORY + ")",
                "  /quit               disconnect and exit",
                "  //text              send text that starts with '/'");
    }

    private static Command direct(String rest) {
        String[] parts = rest.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isBlank()) {
            return usage("/dm <user> <message>");
        }
        return new Command.Direct(stripAt(parts[0]), parts[1].strip());
    }

    private static Command history(String rest) {
        if (rest.isEmpty()) {
            return new Command.History(DEFAULT_HISTORY);
        }
        try {
            int count = Integer.parseInt(firstWord(rest));
            if (count < 1 || count > MAX_HISTORY) {
                return new Command.Invalid("History size must be between 1 and " + MAX_HISTORY);
            }
            return new Command.History(count);
        } catch (NumberFormatException e) {
            return usage("/history [n]");
        }
    }

    private static String firstWord(String text) {
        return text.split("\\s+", 2)[0];
    }

    static String stripAt(String user) {
        return user.startsWith("@") ? user.substring(1) : user;
    }

    private static Command usage(String usage) {
        return new Command.Invalid("Usage: " + usage);
    }
}
