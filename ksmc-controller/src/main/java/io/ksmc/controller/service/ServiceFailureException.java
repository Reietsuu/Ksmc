package io.ksmc.controller.service;

/** Erreur définitive de configuration d'un service ; {@code reason} devient la raison de la phase {@code Failed}. */
public final class ServiceFailureException extends Exception {
    private final String reason;

    public ServiceFailureException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
