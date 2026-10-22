package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.bridge.PlayerAction;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.bridge.ServiceInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkCommandsTest {
    final List<PlayerInfo> players = new ArrayList<>();
    final List<ServiceInfo> services = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    final NetworkCommands commands = new NetworkCommands(new NetworkCommands.Network() {
        public List<PlayerInfo> players() {
            return players;
        }

        public Collection<ServiceInfo> services() {
            return services;
        }

        public String act(String proxy, PlayerAction a) {
            calls.add(proxy + " " + a.type + " " + a.player + " " + a.targetService + " " + a.targetTask);
            return "ok";
        }

        public void broadcast(String text) {
            calls.add("broadcast " + text);
        }
    }, "[A] ");

    static PlayerInfo player(String name, String proxy, String server) {
        PlayerInfo p = new PlayerInfo();
        p.uuid = "uuid-" + name;
        p.name = name;
        p.proxy = proxy;
        p.server = server;
        return p;
    }

    NetworkCommandsTest() {
        services.add(RouterTest.svc("lobby-1", "lobby", 2, true, 10));
        services.add(RouterTest.svc("bedwars-1", "bedwars", 1, false, 0));
        players.add(player("Steve", "proxy-1", "lobby-1"));
        players.add(player("alex", "proxy-2", "lobby-1"));
        players.add(player("Bob", "proxy-2", "bedwars-1"));
    }

    List<String> run(String name, String... args) {
        return commands.execute(RouterTest.ALL, name, args);
    }

    @Test
    void glistCountsTheWholeNetworkPerService() {
        assertThat(run("glist")).containsExactly("&e3 joueur(s) en ligne sur le réseau",
                "&7bedwars-1 &f(1) : Bob", "&7lobby-1 &f(2) : alex, Steve");
        assertThat(run("glist", "bedwars")).containsExactly("&e1 joueur(s) en ligne sur la task bedwars",
                "&7bedwars-1 &f(1) : Bob");
    }

    @Test
    void findLocatesAPlayerOnAnyProxy() {
        assertThat(run("find", "ALEX")).containsExactly("&ealex &7est sur &flobby-1 &7(proxy proxy-2)");
        assertThat(run("find", "Herobrine")).containsExactly("&cHerobrine n'est pas en ligne.");
    }

    @Test
    void sendTargetsAServiceOrATaskThroughThePlayersProxy() {
        assertThat(run("send", "Bob", "lobby-1")).containsExactly("&aBob envoyé vers lobby-1.");
        assertThat(run("send", "Bob", "lobby")).containsExactly("&aBob envoyé vers lobby.");
        assertThat(calls).containsExactly("proxy-2 SEND uuid-Bob lobby-1 null", "proxy-2 SEND uuid-Bob null lobby");
        assertThat(run("send", "Bob", "nowhere")).containsExactly("&cService ou task inconnu : nowhere");
        assertThat(run("send", "Bob")).containsExactly("&cUsage : /send <joueur> <service|task>");
    }

    @Test
    void alertBroadcastsWithPrefix() {
        assertThat(run("alert", "Redémarrage", "dans", "5", "min")).containsExactly("&aAnnonce envoyée.");
        assertThat(calls).containsExactly("broadcast [A] Redémarrage dans 5 min");
    }

    @Test
    void permissionIsRequired() {
        assertThat(commands.execute(p -> !p.equals("ksmc.command.send"), "send", new String[] {"Bob", "lobby"}))
                .containsExactly("&cTu n'as pas la permission.");
        assertThat(calls).isEmpty();
        assertThat(commands.execute(p -> p.equals("ksmc.command.glist"), "glist", new String[0])).hasSize(3);
    }
}
