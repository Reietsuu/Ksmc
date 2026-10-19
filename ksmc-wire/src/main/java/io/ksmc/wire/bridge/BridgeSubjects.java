package io.ksmc.wire.bridge;

/** Sujets et buckets propres au bridge. */
public final class BridgeSubjects {
    public static final String SERVICES_BUCKET = "services";
    public static final String PLAYERS_BUCKET = "players";

    private BridgeSubjects() {
    }

    public static String report(String ns, String service) {
        return "ksmc." + ns + ".report." + service;
    }

    public static String reports(String ns) {
        return "ksmc." + ns + ".report.*";
    }

    /** Événement joueur publié par un proxy : {@code login}, {@code switch}, {@code disconnect}. */
    public static String playerEvent(String ns, String type) {
        return "ksmc." + ns + ".pev." + type;
    }

    public static String playerEvents(String ns) {
        return "ksmc." + ns + ".pev.*";
    }

    /** Actions joueurs à destination d'un proxy ou d'un serveur. */
    public static String playerRpc(String ns, String component) {
        return "ksmc." + ns + ".rpc.player." + component;
    }
}
