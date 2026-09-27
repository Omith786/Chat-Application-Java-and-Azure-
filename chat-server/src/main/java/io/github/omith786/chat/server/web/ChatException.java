package io.github.omith786.chat.server.web;

import io.github.omith786.chat.protocol.ErrorCode;

/**
 * A request that was rejected for a reason the client should hear about. The same exception is
 * turned into an HTTP problem response by {@link ApiExceptionHandler} or into an error frame by
 * the WebSocket handler, so both interfaces report failures identically.
 */
public class ChatException extends RuntimeException {

    private final String code;

    /**
     * @param code    one of the {@link ErrorCode} constants
     * @param message human-readable explanation
     */
    public ChatException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The machine-readable {@link ErrorCode}. */
    public String code() {
        return code;
    }

    /** Wraps a validation failure raised by {@code ChatRules}. */
    public static ChatException invalid(IllegalArgumentException cause) {
        return new ChatException(ErrorCode.INVALID, cause.getMessage());
    }
}
