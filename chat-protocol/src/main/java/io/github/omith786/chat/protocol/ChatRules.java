package io.github.omith786.chat.protocol;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation and normalisation rules shared by the server and the clients.
 *
 * <p>The server is the authority; clients use the same rules only to fail fast with a helpful
 * message before a round trip.
 */
public final class ChatRules {

    /** Usernames are stored lower-case, so "Alice" and "alice" are the same person. */
    public static final Pattern USERNAME = Pattern.compile("[a-z0-9_-]{3,20}");
    /** Room ids are URL-safe slugs. */
    public static final Pattern ROOM_ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?");

    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MIN_ROOM_NAME_LENGTH = 2;
    public static final int MAX_ROOM_NAME_LENGTH = 40;
    public static final int MAX_ROOM_DESCRIPTION_LENGTH = 200;
    public static final int MAX_ROOM_ID_LENGTH = 32;

    /** Names that would be confusing if a person could claim them. */
    private static final Set<String> RESERVED_USERNAMES =
            Set.of("system", "server", "admin", "moderator", "everyone");

    // Everything in the Unicode "Other" categories (controls, format characters such as
    // bidi overrides, unassigned code points) except newline and tab.
    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[\\p{C}&&[^\\n\\t]]");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private ChatRules() {
    }

    /**
     * Normalises a username (trim, lower-case) and checks it.
     *
     * @return the canonical username
     * @throws IllegalArgumentException with a user-facing reason if it is not acceptable
     */
    public static String normaliseUsername(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        String username = raw.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3 to 20 characters: letters, digits, '_' or '-'");
        }
        if (RESERVED_USERNAMES.contains(username)) {
            throw new IllegalArgumentException("That username is reserved");
        }
        return username;
    }

    /** Returns {@code true} if {@code value} is already a canonical, acceptable username. */
    public static boolean isValidUsername(String value) {
        return value != null && USERNAME.matcher(value).matches() && !RESERVED_USERNAMES.contains(value);
    }

    /** Returns {@code true} if {@code value} is a well-formed room id. */
    public static boolean isValidRoomId(String value) {
        return value != null && ROOM_ID.matcher(value).matches();
    }

    /**
     * Cleans message text: normalises Unicode to NFC, drops control and invisible format
     * characters (keeping newlines and tabs), normalises line endings and trims.
     *
     * @return the cleaned text
     * @throws IllegalArgumentException if nothing is left or the text is too long
     */
    public static String normaliseContent(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Message is empty");
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        text = DISALLOWED_CHARS.matcher(text).replaceAll("").strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Message is empty");
        }
        if (text.codePointCount(0, text.length()) > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "Message is longer than " + MAX_MESSAGE_LENGTH + " characters");
        }
        return text;
    }

    /**
     * Cleans a room display name (single line, trimmed).
     *
     * @throws IllegalArgumentException if it is too short or too long after cleaning
     */
    public static String normaliseRoomName(String raw) {
        String name = raw == null ? "" : singleLine(raw);
        int length = name.codePointCount(0, name.length());
        if (length < MIN_ROOM_NAME_LENGTH || length > MAX_ROOM_NAME_LENGTH) {
            throw new IllegalArgumentException("Room name must be " + MIN_ROOM_NAME_LENGTH + " to "
                    + MAX_ROOM_NAME_LENGTH + " characters");
        }
        return name;
    }

    /**
     * Cleans an optional room description.
     *
     * @return the cleaned description, or an empty string
     * @throws IllegalArgumentException if it is too long
     */
    public static String normaliseRoomDescription(String raw) {
        String description = raw == null ? "" : singleLine(raw);
        if (description.codePointCount(0, description.length()) > MAX_ROOM_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Description is longer than " + MAX_ROOM_DESCRIPTION_LENGTH + " characters");
        }
        return description;
    }

    /**
     * Derives a room id from a display name, e.g. "Java &amp; Azure!" becomes "java-azure".
     *
     * @throws IllegalArgumentException if the name has no letters or digits to build an id from
     */
    public static String slugify(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        String slug = NON_SLUG.matcher(ascii).replaceAll("-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_ROOM_ID_LENGTH) {
            slug = slug.substring(0, MAX_ROOM_ID_LENGTH).replaceAll("-+$", "");
        }
        if (!isValidRoomId(slug)) {
            throw new IllegalArgumentException("Room name must contain some letters or digits");
        }
        return slug;
    }

    /**
     * The storage key for a direct-message conversation. It is the same whichever of the two
     * users sends, so both directions share one history.
     */
    public static String directChannel(String userA, String userB) {
        return userA.compareTo(userB) <= 0
                ? "dm:" + userA + ":" + userB
                : "dm:" + userB + ":" + userA;
    }

    /** The storage key for a room's messages. */
    public static String roomChannel(String roomId) {
        return "room:" + roomId;
    }

    private static String singleLine(String raw) {
        return DISALLOWED_CHARS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC))
                .replaceAll("")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
