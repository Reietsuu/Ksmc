package io.ksmc.bridge;

import java.util.List;

/**
 * Adaptateur d'une plateforme (Velocity, BungeeCord, Bukkit, Fabric, NeoForge). Les méthodes sans objet pour
 * une plateforme (ex. {@link #registerServer} sur un serveur de jeu) ne sont jamais appelées.
 */
public interface Platform {

    /** Joueur en ligne sur ce composant. */
    final class OnlinePlayer {
        public final String uuid;
        public final String name;
        /** Serveur actuel (proxy) ou {@code null}. */
        public final String server;
        public final int protocolVersion;

        public OnlinePlayer(String uuid, String name, String server, int protocolVersion) {
            this.uuid = uuid;
            this.name = name;
            this.server = server;
            this.protocolVersion = protocolVersion;
        }
    }

    String name();

    void log(String message);

    // --- Proxies ----------------------------------------------------------------------------------

    void registerServer(String name, String host, int port);

    void unregisterServer(String name);

    // --- Joueurs (proxies et serveurs) ------------------------------------------------------------

    List<OnlinePlayer> onlinePlayers();

    /** Envoie le joueur vers un serveur (proxies) ; faux si le joueur ou le serveur est inconnu. */
    boolean sendPlayer(String player, String server);

    boolean kickPlayer(String player, String reason);

    boolean messagePlayer(String player, String message);

    boolean titlePlayer(String player, String title, String subtitle);

    // --- État (serveurs et proxies) ---------------------------------------------------------------

    int maxPlayers();

    String motd();

    /** TPS actuels, ou {@code null} si indisponible. */
    Double tps();
}
