package io.github.omith786.chat.protocol;

/**
 * Application-specific WebSocket close codes (the 4000 to 4999 range is reserved for
 * applications by RFC 6455). They mirror HTTP statuses so they are easy to remember.
 */
public final class CloseCodes {

    /** The session token was missing, forged or expired: sign in again. */
    public static final int INVALID_TOKEN = 4401;
    /** No auth frame arrived in time after connecting. */
    public static final int AUTH_TIMEOUT = 4408;
    /** The user already has the maximum number of connections open. */
    public static final int TOO_MANY_CONNECTIONS = 4409;

    private CloseCodes() {
    }
}
