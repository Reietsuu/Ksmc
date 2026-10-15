package io.ksmc.wire.messaging;

/** Erreur de messagerie : {@code code} = {@code Timeout}, {@code NoResponders}, {@code Conflict}, {@code Forbidden}... */
public class MessagingException extends RuntimeException {
    private final String code;

    public MessagingException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public MessagingException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
