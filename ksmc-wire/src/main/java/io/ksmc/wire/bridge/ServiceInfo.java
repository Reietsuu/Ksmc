package io.ksmc.wire.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vue d'un service démarré, publiée par le controller dans le bucket KV {@code services} et lue par les bridges
 * (enregistrement dans les proxies, choix du fallback, informations pour les plugins).
 */
public class ServiceInfo {
    public String name;
    public String task;
    /** {@code proxy} ou {@code server}. */
    public String environment;
    /** Phase du service (Starting, Running...). */
    public String phase;
    public String host;
    public int port = 25565;
    public int onlinePlayers;
    public int maxPlayers;
    public String motd;
    /** État personnalisé (LOBBY, INGAME...). */
    public String state;
    public Double tps;
    public boolean fallback;
    public int fallbackPriority;
    public String fallbackPermission;
    /** Service préparé (warm pool) : ne reçoit pas de joueurs. */
    public boolean prepared;
    public boolean maintenance;
    public Map<String, String> properties = new LinkedHashMap<String, String>();
    public long updatedAt;

    public boolean isRunning() {
        return "Running".equals(phase) && host != null;
    }

    public boolean isServer() {
        return "server".equals(environment);
    }

    /** Taux de remplissage (0 si pas de maximum connu). */
    public double fillRatio() {
        return maxPlayers <= 0 ? 0 : (double) onlinePlayers / maxPlayers;
    }
}
