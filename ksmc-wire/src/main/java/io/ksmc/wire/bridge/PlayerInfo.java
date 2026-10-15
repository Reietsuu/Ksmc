package io.ksmc.wire.bridge;

/** Joueur connecté au réseau (bucket KV {@code players}, clé = UUID). */
public class PlayerInfo {
    public String uuid;
    public String name;
    public String proxy;
    /** Serveur actuel, ou {@code null} pendant une connexion. */
    public String server;
    public long connectedAt;
    public int protocolVersion;
}
