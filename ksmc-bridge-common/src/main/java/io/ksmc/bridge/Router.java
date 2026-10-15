package io.ksmc.bridge;

import io.ksmc.wire.bridge.ServiceInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Règles de routage des joueurs dans un proxy (fonctions pures, testées unitairement). */
public final class Router {
    /** Vérifie une permission d'un joueur (toujours vrai si la permission est nulle). */
    public interface Permissions {
        boolean has(String permission);
    }

    private static final Comparator<ServiceInfo> LEAST_FILLED = new Comparator<ServiceInfo>() {
        public int compare(ServiceInfo a, ServiceInfo b) {
            int c = Integer.compare(a.onlinePlayers, b.onlinePlayers);
            if (c != 0) {
                return c;
            }
            c = Double.compare(a.fillRatio(), b.fillRatio());
            return c != 0 ? c : a.name.compareTo(b.name);
        }
    };

    private Router() {
    }

    /** Service de jeu utilisable pour recevoir des joueurs. */
    static boolean routable(ServiceInfo s) {
        return s.isServer() && s.isRunning() && !s.prepared;
    }

    /**
     * Fallback : parmi les services {@code fallback} accessibles, ceux de la plus haute priorité, puis le moins rempli.
     *
     * @param exclude service à éviter (celui dont le joueur vient d'être expulsé), ou {@code null}
     */
    public static ServiceInfo fallback(Collection<ServiceInfo> services, Permissions perms, String exclude) {
        List<ServiceInfo> candidates = new ArrayList<ServiceInfo>();
        for (ServiceInfo s : services) {
            if (routable(s) && s.fallback && !s.name.equals(exclude)
                    && (s.fallbackPermission == null || s.fallbackPermission.isEmpty() || perms.has(s.fallbackPermission))
                    && (!s.maintenance || perms.has("ksmc.maintenance.bypass"))) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int best = Integer.MIN_VALUE;
        for (ServiceInfo s : candidates) {
            best = Math.max(best, s.fallbackPriority);
        }
        List<ServiceInfo> top = new ArrayList<ServiceInfo>();
        for (ServiceInfo s : candidates) {
            if (s.fallbackPriority == best) {
                top.add(s);
            }
        }
        return Collections.min(top, LEAST_FILLED);
    }

    /** Service le moins rempli d'une task, ou {@code null}. */
    public static ServiceInfo leastFilled(Collection<ServiceInfo> services, String task) {
        ServiceInfo best = null;
        for (ServiceInfo s : services) {
            if (routable(s) && task.equals(s.task) && (best == null || LEAST_FILLED.compare(s, best) < 0)) {
                best = s;
            }
        }
        return best;
    }

    /** Task cible d'un nom d'hôte de connexion selon les forced hosts ({@code ksmc.forced-host.<hôte>}), ou {@code null}. */
    public static String forcedHostTask(Map<String, String> proxyProperties, String virtualHost) {
        if (virtualHost == null) {
            return null;
        }
        String host = virtualHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return proxyProperties.get("ksmc.forced-host." + host);
    }

    /** Un joueur peut-il rejoindre un proxy en maintenance ? (permission de contournement ou liste blanche). */
    public static boolean allowedDuringMaintenance(Map<String, String> proxyProperties, String playerName, Permissions perms) {
        if (!"true".equals(proxyProperties.get("ksmc.maintenance"))) {
            return true;
        }
        if (perms.has("ksmc.maintenance.bypass")) {
            return true;
        }
        String whitelist = proxyProperties.get("ksmc.maintenance.whitelist");
        if (whitelist != null) {
            for (String name : whitelist.split(",")) {
                if (name.trim().equalsIgnoreCase(playerName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
