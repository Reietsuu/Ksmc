package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.bridge.ServiceInfo;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouterTest {
    static ServiceInfo svc(String name, String task, int players, boolean fallback, int priority) {
        ServiceInfo s = new ServiceInfo();
        s.name = name;
        s.task = task;
        s.environment = "server";
        s.phase = "Running";
        s.host = "10.0.0.1";
        s.onlinePlayers = players;
        s.maxPlayers = 20;
        s.fallback = fallback;
        s.fallbackPriority = priority;
        return s;
    }

    static final Router.Permissions NONE = p -> false;
    static final Router.Permissions ALL = p -> true;

    @Test
    void fallbackPicksHighestPriorityThenLeastFilled() {
        List<ServiceInfo> services = Arrays.asList(
                svc("lobby-1", "lobby", 8, true, 10),
                svc("lobby-2", "lobby", 3, true, 10),
                svc("hub-1", "hub", 0, true, 5),
                svc("game-1", "game", 0, false, 0));

        assertThat(Router.fallback(services, NONE, null).name).isEqualTo("lobby-2");
        assertThat(Router.fallback(services, NONE, "lobby-2").name).isEqualTo("lobby-1");
    }

    @Test
    void fallbackRespectsPermissionsPreparedAndPhase() {
        ServiceInfo vip = svc("vip-1", "vip", 0, true, 100);
        vip.fallbackPermission = "lobby.vip";
        ServiceInfo prepared = svc("lobby-3", "lobby", 0, true, 50);
        prepared.prepared = true;
        ServiceInfo starting = svc("lobby-4", "lobby", 0, true, 50);
        starting.phase = "Starting";
        List<ServiceInfo> services = Arrays.asList(vip, prepared, starting, svc("lobby-1", "lobby", 5, true, 10));

        assertThat(Router.fallback(services, NONE, null).name).isEqualTo("lobby-1");
        assertThat(Router.fallback(services, ALL, null).name).isEqualTo("vip-1");
    }

    @Test
    void noFallbackReturnsNull() {
        assertThat(Router.fallback(Arrays.asList(svc("game-1", "game", 0, false, 0)), NONE, null)).isNull();
    }

    @Test
    void leastFilledOfTask() {
        List<ServiceInfo> services = Arrays.asList(svc("bw-1", "bedwars", 7, false, 0), svc("bw-2", "bedwars", 2, false, 0),
                svc("lobby-1", "lobby", 0, true, 0));
        assertThat(Router.leastFilled(services, "bedwars").name).isEqualTo("bw-2");
        assertThat(Router.leastFilled(services, "skywars")).isNull();
    }

    @Test
    void forcedHostsAndMaintenance() {
        Map<String, String> props = new HashMap<>();
        props.put("ksmc.forced-host.bedwars.example.com", "bedwars");
        props.put("ksmc.maintenance", "true");
        props.put("ksmc.maintenance.whitelist", "Steve, Alex");

        assertThat(Router.forcedHostTask(props, "BedWars.Example.com.")).isEqualTo("bedwars");
        assertThat(Router.forcedHostTask(props, "play.example.com")).isNull();
        assertThat(Router.allowedDuringMaintenance(props, "alex", NONE)).isTrue();
        assertThat(Router.allowedDuringMaintenance(props, "Notch", NONE)).isFalse();
        assertThat(Router.allowedDuringMaintenance(props, "Notch", ALL)).isTrue();
        assertThat(Router.allowedDuringMaintenance(new HashMap<>(), "Notch", NONE)).isTrue();
    }
}
