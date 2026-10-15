package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudServiceStatus extends KsmcStatus {
    public enum Phase { Pending, Preparing, Starting, Running, Stopping, Stopped, Failed }

    public Phase phase;
    /** Raison courte (CamelCase) de la phase, ex. {@code SoftwareResolutionFailed}. */
    public String reason;
    public String message;
    public String podName;
    /** {@code ip:port} joignable dans le cluster. */
    public String address;
    /** Version effective du logiciel après résolution ({@code latest} → numéro). */
    public String softwareVersion;
    public Integer onlinePlayers;
    public Integer maxPlayers;
    /** Affichage {@code en ligne/max} pour kubectl. */
    public String players;
    public String motd;
    /** État personnalisé défini par les plugins (ex. LOBBY, INGAME). */
    public String state;
    public Double tps;
    public String startedAt;
    public String readyAt;
    public String lastPlayerAt;
    /** Propriétés libres définies par les plugins via le bridge. */
    public Map<String, String> properties = new LinkedHashMap<>();
}
