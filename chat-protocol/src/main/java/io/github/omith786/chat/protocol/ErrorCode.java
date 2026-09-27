package io.github.omith786.chat.protocol;

/** Machine-readable error codes carried by {@link ServerFrame.Error}. */
public final class ErrorCode {

    /** The frame was not valid JSON or had an unknown {@code type}. */
    public static final String BAD_FRAME = "bad_frame";
    /** A field failed validation (length, allowed characters and so on). */
    public static final String INVALID = "invalid";
    /** The connection has not authenticated yet, or the token was rejected. */
    public static final String UNAUTHORISED = "unauthorised";
    /** The client is sending faster than the configured rate limit allows. */
    public static final String RATE_LIMITED = "rate_limited";
    /** The room does not exist. */
    public static final String NO_SUCH_ROOM = "no_such_room";
    /** The connection must join the room before posting to it. */
    public static final String NOT_IN_ROOM = "not_in_room";
    /** Direct messages can only be sent to users who are currently online. */
    public static final String USER_OFFLINE = "user_offline";
    /** A configured limit was reached (connections per user, rooms per connection). */
    public static final String LIMIT_REACHED = "limit_reached";
    /** Someone with that username is already online. */
    public static final String USERNAME_TAKEN = "username_taken";
    /** A room with that id already exists. */
    public static final String ROOM_EXISTS = "room_exists";
    /** The requested resource does not exist. */
    public static final String NOT_FOUND = "not_found";
    /** An unexpected server-side failure. */
    public static final String INTERNAL = "internal";

    private ErrorCode() {
    }
}
