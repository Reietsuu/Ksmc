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
    public Map<String, String> properties = new LinkedHashMap<String, String>();
    public long timestamp = System.currentTimeMillis();
}
