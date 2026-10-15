package io.ksmc.controller.service;

/** La ressource demandée par un Pod n'est pas encore prête (téléchargement, construction) : réessayer plus tard. */
public final class RetryLaterException extends Exception {
    private final int seconds;

    public RetryLaterException(String message, int seconds) {
        super(message);
        this.seconds = seconds;
    }

    public int seconds() {
        return seconds;
    }
}
