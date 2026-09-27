package io.github.omith786.chat.cli;

import io.github.omith786.chat.protocol.ServerFrame;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats server frames as terminal lines, optionally with ANSI colour. Returns {@code null} for
 * frames that are not worth printing (acks, pongs, typing signals).
 */
public final class Renderer {

    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String RED = "\u001B[31m";
    // Colours that read well on both light and dark terminals.
    private static final String[] NAME_COLOURS = {
            "\u001B[31m", "\u001B[32m", "\u001B[33m", "\u001B[34m", "\u001B[35m", "\u001B[36m"};

    private final boolean colour;
    private final DateTimeFormatter time;
    private final String me;

    /**
     * @param me     the signed-in user, shown as "you" in direct messages
     * @param colour whether to emit ANSI escape codes
     * @param zone   time zone for timestamps
     */
    public Renderer(String me, boolean colour, ZoneId zone) {
        this.me = me;
        this.colour = colour;
        this.time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone);
    }

    /** One or more lines for a frame, or {@code null} to print nothing. */
    public String render(ServerFrame frame) {
        return switch (frame) {
            case ServerFrame.RoomMessage m -> roomMessage(m);
            case ServerFrame.DirectMessage m -> directMessage(m);
            case ServerFrame.Presence p -> notice(p.user() + (p.online() ? " is online" : " went offline"));
            case ServerFrame.Membership m -> m.user().equals(me) ? null
                    : notice(m.user() + (m.joined() ? " joined #" : " left #") + m.room());
            case ServerFrame.Joined j -> notice("You joined #" + j.room() + " (" + String.join(", ", j.members()) + ")");
            case ServerFrame.Left l -> notice("You left #" + l.room());
            case ServerFrame.RoomCreated r -> notice("New room #" + r.id() + " (" + r.name() + ") created by " + r.createdBy());
            case ServerFrame.Error e -> error(e.message());
            case ServerFrame.Welcome w -> notice("Signed in as " + w.user() + " on " + w.instance()
                    + ". Online: " + String.join(", ", w.online()));
            case ServerFrame.Ack a -> null;
            case ServerFrame.Pong p -> null;
            case ServerFrame.Typing t -> null;
        };
    }

    /** A room message line, e.g. {@code [12:03] #general <alice> hello}. */
    public String roomMessage(ServerFrame.RoomMessage m) {
        return stamp(m.sentAt()) + paint(DIM, "#" + m.room()) + " <" + name(m.from()) + "> " + m.content();
    }

    /** A direct message line, e.g. {@code [12:03] [dm] alice -> you: hi}. */
    public String directMessage(ServerFrame.DirectMessage m) {
        String from = m.from().equals(me) ? "you" : name(m.from());
        String to = m.to().equals(me) ? "you" : name(m.to());
        return stamp(m.sentAt()) + paint(MAGENTA, "[dm] ") + from + " -> " + to + ": " + m.content();
    }

    /** An informational line. */
    public String notice(String text) {
        return paint(CYAN, "* " + text);
    }

    /** An error line. */
    public String error(String text) {
        return paint(RED, "! " + text);
    }

    private String stamp(Instant at) {
        return paint(DIM, "[" + time.format(at) + "] ");
    }

    private String name(String user) {
        if (!colour) {
            return user;
        }
        String c = NAME_COLOURS[Math.floorMod(user.hashCode(), NAME_COLOURS.length)];
        return BOLD + c + user + RESET;
    }

    private String paint(String code, String text) {
        return colour ? code + text + RESET : text;
    }
}
