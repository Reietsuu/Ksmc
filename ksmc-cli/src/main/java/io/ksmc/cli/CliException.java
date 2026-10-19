package io.ksmc.cli;

/** Erreur affichée sur stderr ; {@code exitCode} : 1 (usage), 2 (API, réseau). */
public final class CliException extends RuntimeException {

    private final int exitCode;

    public CliException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }

    public static CliException api(String message) {
        return new CliException(2, message);
    }

    public static CliException usage(String message) {
        return new CliException(1, message);
    }
}
