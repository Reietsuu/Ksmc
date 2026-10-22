package io.ksmc.bridge;

import io.ksmc.wire.bridge.PlayerAction;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.bridge.ServiceInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Commandes réseau des proxies ({@code glist}, {@code find}, {@code send}, {@code alert}) : elles agissent sur tout
 * le réseau, quel que soit le proxy du joueur. Indépendantes de la plateforme : renvoient des lignes (codes {@code &}).
 */
public final class NetworkCommands {
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList("glist", "find", "send", "alert"));
    public static final String PERMISSION_PREFIX = "ksmc.command.";

    /** Vue du réseau utilisée par les commandes. */
    public interface Network {
        /** Joueurs en ligne sur tout le réseau. */
        List<PlayerInfo> players();

        Collection<ServiceInfo> services();

        /** Exécute l'action sur le proxy du joueur ; {@code ok} ou {@code error: ...}. */
        String act(String proxy, PlayerAction action);

        void broadcast(String text);
    }

    private final Network network;
    private final String alertPrefix;

    public NetworkCommands(Network network, String alertPrefix) {
        this.network = network;
        this.alertPrefix = alertPrefix;
    }

    /** Exécute la commande {@code name} ; {@code perms} : permissions de l'expéditeur (console : toutes). */
    public List<String> execute(Router.Permissions perms, String name, String[] args) {
        if (!NAMES.contains(name)) {
            return lines("&cCommandes : /ksmc <" + join(NAMES, "|") + ">");
        }
        if (!perms.has(PERMISSION_PREFIX + name)) {
            return lines("&cTu n'as pas la permission.");
        }
        try {
            if ("glist".equals(name)) {
                return glist(args.length > 0 ? args[0] : null);
            }
            if ("find".equals(name)) {
                return args.length < 1 ? lines("&cUsage : /find <joueur>") : find(args[0]);
            }
            if ("send".equals(name)) {
                return args.length < 2 ? lines("&cUsage : /send <joueur> <service|task>") : send(args[0], args[1]);
            }
            return args.length < 1 ? lines("&cUsage : /alert <message>") : alert(join(Arrays.asList(args), " "));
        } catch (RuntimeException e) {
            return lines("&cCloud injoignable : " + e.getMessage());
        }
    }

    private List<String> glist(String task) {
        Map<String, String> tasks = new TreeMap<String, String>();
        for (ServiceInfo s : network.services()) {
            tasks.put(s.name, s.task);
        }
        Map<String, List<String>> byServer = new TreeMap<String, List<String>>();
        int total = 0;
        for (PlayerInfo p : network.players()) {
            String server = p.server == null ? "(connexion)" : p.server;
            if (task != null && !task.equals(tasks.get(server))) {
                continue;
            }
            total++;
            List<String> names = byServer.get(server);
            if (names == null) {
                names = new ArrayList<String>();
                byServer.put(server, names);
            }
            names.add(p.name);
        }
        List<String> out = new ArrayList<String>();
        out.add("&e" + total + " joueur(s) en ligne" + (task == null ? " sur le réseau" : " sur la task " + task));
        for (Map.Entry<String, List<String>> e : byServer.entrySet()) {
            Collections.sort(e.getValue(), String.CASE_INSENSITIVE_ORDER);
            out.add("&7" + e.getKey() + " &f(" + e.getValue().size() + ") : " + join(e.getValue(), ", "));
        }
        return out;
    }

    private PlayerInfo player(String name) {
        for (PlayerInfo p : network.players()) {
            if (p.name.equalsIgnoreCase(name) || p.uuid.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private List<String> find(String name) {
        PlayerInfo p = player(name);
        if (p == null) {
            return lines("&c" + name + " n'est pas en ligne.");
        }
        return lines("&e" + p.name + " &7est sur &f" + (p.server == null ? "(connexion)" : p.server)
                + " &7(proxy " + p.proxy + ")");
    }

    private List<String> send(String name, String target) {
        PlayerInfo p = player(name);
        if (p == null) {
            return lines("&c" + name + " n'est pas en ligne.");
        }
        PlayerAction a = PlayerAction.of(PlayerAction.Type.SEND, p.uuid);
        ServiceInfo service = null;
        boolean task = false;
        for (ServiceInfo s : network.services()) {
            if (!"proxy".equals(s.environment)) {
                service = target.equals(s.name) ? s : service;
                task |= target.equals(s.task);
            }
        }
        if (service != null) {
            a.targetService = service.name;
        } else if (task) {
            a.targetTask = target;
        } else {
            return lines("&cService ou task inconnu : " + target);
        }
        String reply = network.act(p.proxy, a);
        return "ok".equals(reply) ? lines("&a" + p.name + " envoyé vers " + target + ".")
                : lines("&cÉchec : " + reply.replaceFirst("^error: ", ""));
    }

    private List<String> alert(String message) {
        network.broadcast(alertPrefix + message);
        return lines("&aAnnonce envoyée.");
    }

    private static List<String> lines(String line) {
        return Collections.singletonList(line);
    }

    private static String join(Collection<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) {
                b.append(sep);
            }
            b.append(p);
        }
        return b.toString();
    }
}
