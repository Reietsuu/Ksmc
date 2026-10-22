package io.ksmc.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan de drainage d'un proxy qui s'arrête : chaque joueur est transféré (paquet Transfer, 1.20.5+) vers un autre
 * proxy de la task via l'adresse publique, ou déconnecté avec un message de repli.
 */
public final class TransferPlan {
    /** Premier protocole qui accepte le paquet Transfer (1.20.5). */
    public static final int MIN_PROTOCOL = 766;
    static final int DEFAULT_PORT = 25565;

    /** Joueur à drainer. {@code virtualHost} : adresse utilisée pour se connecter ({@code hôte[:port]}), ou {@code null}. */
    public static final class Candidate {
        public final String uuid;
        public final int protocolVersion;
        public final String virtualHost;
        public final int virtualPort;

        public Candidate(String uuid, int protocolVersion, String virtualHost, int virtualPort) {
            this.uuid = uuid;
            this.protocolVersion = protocolVersion;
            this.virtualHost = virtualHost;
            this.virtualPort = virtualPort;
        }
    }

    /** Décision pour un joueur : transfert vers {@code host:port}, sinon expulsion avec {@code message}. */
    public static final class Step {
        public final String uuid;
        public final String host;
        public final int port;
        public final String message;

        Step(String uuid, String host, int port, String message) {
            this.uuid = uuid;
            this.host = host;
            this.port = port;
            this.message = message;
        }

        public boolean isTransfer() {
            return host != null;
        }
    }

    private TransferPlan() {
    }

    /**
     * @param otherProxyRunning un autre proxy {@code Running} de la même task peut recevoir les joueurs
     * @param properties propriétés du proxy ({@code ksmc.transfer.*})
     */
    public static List<Step> plan(List<Candidate> players, boolean otherProxyRunning, Map<String, String> properties) {
        boolean enabled = !"false".equals(properties.get("ksmc.transfer.enabled"));
        String message = fallbackMessage(properties);
        String address = properties.get("ksmc.transfer.address");
        List<Step> out = new ArrayList<Step>();
        for (Candidate c : players) {
            String host = null;
            int port = DEFAULT_PORT;
            if (enabled && otherProxyRunning && c.protocolVersion >= MIN_PROTOCOL) {
                if (address != null && !address.trim().isEmpty()) {
                    String a = address.trim();
                    int colon = a.lastIndexOf(':');
                    host = colon > 0 ? a.substring(0, colon) : a;
                    port = colon > 0 ? Integer.parseInt(a.substring(colon + 1)) : DEFAULT_PORT;
                } else if (c.virtualHost != null && !c.virtualHost.isEmpty()) {
                    host = c.virtualHost;
                    port = c.virtualPort > 0 ? c.virtualPort : DEFAULT_PORT;
                }
            }
            out.add(host != null ? new Step(c.uuid, host, port, null) : new Step(c.uuid, null, 0, message));
        }
        return out;
    }

    static String fallbackMessage(Map<String, String> properties) {
        String m = properties.get("ksmc.transfer.fallback-message");
        return m != null ? m : "Le proxy redémarre, reconnecte-toi.";
    }
}
