package io.ksmc.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Instantané d'un service. */
public final class CloudServiceInfo {

    private final String name;
    private final String task;
    private final String environment;
    private final String phase;
    private final String host;
    private final int port;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final String motd;
    private final String state;
    private final Double tps;
    private final boolean fallback;
    private final boolean maintenance;
    private final Map<String, String> properties;

    public CloudServiceInfo(String name, String task, String environment, String phase, String host, int port,
            int onlinePlayers, int maxPlayers, String motd, String state, Double tps, boolean fallback,
            boolean maintenance, Map<String, String> properties) {
        this.name = name;
        this.task = task;
        this.environment = environment;
        this.phase = phase;
        this.host = host;
        this.port = port;
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
        this.motd = motd;
        this.state = state;
        this.tps = tps;
        this.fallback = fallback;
        this.maintenance = maintenance;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                properties == null ? Collections.<String, String>emptyMap() : properties));
    }

    public String name() {
        return name;
    }

    public String task() {
        return task;
    }

    /** {@code server} ou {@code proxy}. */
    public String environment() {
        return environment;
    }

    /** Phase Kubernetes ({@code Starting}, {@code Running}...). */
    public String phase() {
        return phase;
    }

    public boolean isRunning() {
        return "Running".equals(phase);
    }

    /** Adresse joignable depuis le cluster (proxies, autres services). */
    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public int onlinePlayers() {
        return onlinePlayers;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public String motd() {
        return motd;
    }

    /** État personnalisé défini par un plugin (ex. {@code LOBBY}, {@code INGAME}), ou {@code null}. */
    public String state() {
        return state;
    }

    public Double tps() {
        return tps;
    }

    public boolean isFallback() {
        return fallback;
    }

    public boolean isMaintenance() {
        return maintenance;
    }

    /** Propriétés libres définies par les plugins du service. */
    public Map<String, String> properties() {
        return properties;
    }

    public String property(String key) {
        return properties.get(key);
    }

    @Override
    public String toString() {
        return "CloudServiceInfo{" + name + ", " + phase + ", " + onlinePlayers + "/" + maxPlayers + ", state=" + state + "}";
    }
}
