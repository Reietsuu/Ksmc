package io.ksmc.controller.http;

import io.javalin.http.Context;
import io.ksmc.controller.auth.Permissions;
import java.util.Optional;
import java.util.Set;

/** Authentification et autorisation des appels à l'API publique ({@code /api/v1}). */
public interface ApiAuthenticator {

    /** Utilisateur authentifié, ses permissions effectives et s'il doit d'abord changer son mot de passe. */
    record Principal(String name, Set<String> permissions, boolean mustChangePassword) {

        public Principal(String name, Set<String> permissions) {
            this(name, permissions, false);
        }

        /** Permission exacte, joker {@code *} ou préfixe joker ({@code hello.*}) ; {@code null} : toujours vrai. */
        public boolean has(String permission) {
            return Permissions.grants(permissions, permission);
        }
    }

    /** Utilisateur de la requête, ou vide si elle n'est pas authentifiée. */
    Optional<Principal> authenticate(Context ctx);
}
