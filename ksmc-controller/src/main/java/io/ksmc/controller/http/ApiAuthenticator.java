package io.ksmc.controller.http;

import io.javalin.http.Context;
import java.util.Optional;

/** Authentification et autorisation des appels à l'API publique ({@code /api/v1}). */
public interface ApiAuthenticator {

    /** Utilisateur authentifié et ses permissions. */
    record Principal(String name, java.util.Set<String> permissions) {

        /** Permission exacte, joker {@code *} ou préfixe joker ({@code hello.*}). */
        public boolean has(String permission) {
            if (permission == null || permissions.contains("*") || permissions.contains(permission)) {
                return true;
            }
            for (int i = permission.lastIndexOf('.'); i > 0; i = permission.lastIndexOf('.', i - 1)) {
                if (permissions.contains(permission.substring(0, i) + ".*")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Utilisateur de la requête, ou vide si elle n'est pas authentifiée. */
    Optional<Principal> authenticate(Context ctx);
}
