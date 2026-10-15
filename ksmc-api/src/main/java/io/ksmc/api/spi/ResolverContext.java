package io.ksmc.api.spi;

import java.io.IOException;

/** Accès réseau fournis aux {@link SoftwareResolver} (remplaçable en test). */
public interface ResolverContext {

    /** GET HTTP renvoyant le corps en texte ; lève une exception si le statut n'est pas 2xx. */
    String getString(String url) throws IOException;

    /** Comme {@link #getString} mais renvoie {@code null} sur 404. */
    default String getStringOrNull(String url) throws IOException {
        try {
            return getString(url);
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    class HttpStatusException extends IOException {
        private final int status;

        public HttpStatusException(int status, String url) {
            super("HTTP " + status + " for " + url);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
