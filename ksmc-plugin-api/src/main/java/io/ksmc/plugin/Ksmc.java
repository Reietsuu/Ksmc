package io.ksmc.plugin;

/** Point d'entrée de l'API, identique sur toutes les plateformes. */
public final class Ksmc {

    /** Version de cette API (SemVer : la majeure change seulement en cas d'incompatibilité). */
    public static final String API_VERSION = "1.0.0";

    private static volatile KsmcPluginApi api;

    private Ksmc() {
    }

    /**
     * API du service courant.
     *
     * @throws IllegalStateException si le bridge Ksmc n'est pas actif (serveur hors Ksmc, bridge désactivé)
     */
    public static KsmcPluginApi get() {
        KsmcPluginApi current = api;
        if (current == null) {
            throw new IllegalStateException("API Ksmc indisponible : le bridge Ksmc n'est pas actif sur ce serveur");
        }
        return current;
    }

    public static boolean isAvailable() {
        return api != null;
    }

    /** Réservé au bridge Ksmc. */
    public static void register(KsmcPluginApi implementation) {
        api = implementation;
    }

    /** Réservé au bridge Ksmc. */
    public static void unregister(KsmcPluginApi implementation) {
        if (api == implementation) {
            api = null;
        }
    }
}
