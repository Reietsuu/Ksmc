package io.ksmc.plugin;

/** Joueur connecté au réseau. */
public final class PlayerInfo {

    private final String uuid;
    private final String name;
    private final String proxy;
    private final String server;
    private final long connectedAt;

    public PlayerInfo(String uuid, String name, String proxy, String server, long connectedAt) {
        this.uuid = uuid;
        this.name = name;
        this.proxy = proxy;
        this.server = server;
        this.connectedAt = connectedAt;
    }

    public String uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    /** Service proxy auquel le joueur est connecté. */
    public String proxy() {
        return proxy;
    }

    /** Serveur actuel, ou {@code null} pendant une connexion. */
    public String server() {
        return server;
    }

    /** Heure de connexion (epoch ms). */
    public long connectedAt() {
        return connectedAt;
    }
}
