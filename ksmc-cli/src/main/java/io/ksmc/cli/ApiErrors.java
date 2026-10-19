package io.ksmc.cli;

/** Conversion d'une réponse d'erreur brute en {@link CliException}. */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static CliException of(int status, byte[] body) {
        return Api.error(status, body);
    }
}
