package io.ksmc.wire.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/** État remonté périodiquement par le bridge d'un serveur ({@code ksmc.<ns>.report.<service>}). */
public class ServiceReport {
    public String service;
    public int onlinePlayers;
    public int maxPlayers;
    public String motd;
    public String state;
    public Double tps;
    /** JVM du serveur (mesurée par le bridge) : tas utilisé et maximal (octets), collectes GC et leur durée cumulée. */
    public Long heapUsedBytes;
    public Long heapMaxBytes;
    public Long gcCount;
    public Long gcTimeMillis;
    /** Raison du gel détecté par le chien de garde du proxy, {@code null} sinon. */
    public String frozen;
    public Map<String, String> properties = new LinkedHashMap<String, String>();
    public long timestamp = System.currentTimeMillis();
}
