package io.github.omith786.chat.cli;

/** A REST call to the chat server failed. */
public class ChatApiException extends RuntimeException {

    private final int status;
    private final String code;

    /**
     * @param status HTTP status, or 0 if the server could not be reached
     * @param code   the server's machine-readable error code, if it sent one
     */
    public ChatApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
