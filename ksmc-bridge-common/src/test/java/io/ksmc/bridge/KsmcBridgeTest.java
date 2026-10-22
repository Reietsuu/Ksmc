package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerAction;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.bridge.ServiceReport;
import io.ksmc.wire.manifest.ServiceManifest;
import io.ksmc.wire.messaging.Identity;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

class KsmcBridgeTest {
    static GenericContainer<?> nats;
    static String url;
    NatsMessaging controller;
    final List<KsmcBridge> bridges = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        nats = new GenericContainer<>("nats:2.11-alpine").withCommand("-js").withExposedPorts(4222);
        nats.start();
        url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
    }

    @AfterAll
    static void stop() {
        nats.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        controller = NatsMessaging.connect(Identity.controller("prod"), url, null, null);
        controller.ensureBucket(BridgeSubjects.SERVICES_BUCKET, Duration.ofMinutes(1));
        controller.ensureBucket(BridgeSubjects.PLAYERS_BUCKET, Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        bridges.forEach(KsmcBridge::close);
        controller.close();
    }

    KsmcBridge bridge(String service, String env, FakePlatform platform, String... properties) throws Exception {
        ServiceManifest m = new ServiceManifest();
        for (int i = 0; i < properties.length; i += 2) {
            m.properties.put(properties[i], properties[i + 1]);
        }
        m.network.maxPlayers = 500;
        m.properties.put("ksmc.forced-host.bw.example.com", "bedwars");
        KsmcBridge b = new KsmcBridge(new BridgeEnv("prod", service, service.replaceAll("-\\d+$", ""), env, url, null, null, m),
                platform);
        bridges.add(b);
        b.connect();
        return b;
    }

    static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timeout");
            }
            Thread.sleep(20);
        }
    }

    void publishService(ServiceInfo s) {
        controller.keyValue(BridgeSubjects.SERVICES_BUCKET).put(s.name, Wire.toJson(s).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void proxyRegistersRunningServersAndUnregistersStoppedOnes() throws Exception {
        publishService(RouterTest.svc("lobby-1", "lobby", 2, true, 10));
        FakePlatform proxy = new FakePlatform();
        KsmcBridge b = bridge("proxy-1", "proxy", proxy);

        await(() -> proxy.servers.containsKey("lobby-1"));
        publishService(RouterTest.svc("lobby-2", "lobby", 0, true, 10));
        await(() -> proxy.servers.containsKey("lobby-2"));
        assertThat(b.initialServer(p -> false, "play.example.com")).isEqualTo("lobby-2");

        ServiceInfo stopping = RouterTest.svc("lobby-2", "lobby", 0, true, 10);
        stopping.phase = "Stopping";
        publishService(stopping);
        await(() -> !proxy.servers.containsKey("lobby-2"));
        controller.keyValue(BridgeSubjects.SERVICES_BUCKET).delete("lobby-1");
        await(proxy.servers::isEmpty);
        assertThat(b.initialServer(p -> false, null)).isNull();
    }

    @Test
    void forcedHostRoutesToTask() throws Exception {
        publishService(RouterTest.svc("lobby-1", "lobby", 0, true, 10));
        publishService(RouterTest.svc("bedwars-1", "bedwars", 0, false, 0));
        FakePlatform proxy = new FakePlatform();
        KsmcBridge b = bridge("proxy-1", "proxy", proxy);
        await(() -> proxy.servers.size() == 2);

        assertThat(b.initialServer(p -> false, "bw.example.com")).isEqualTo("bedwars-1");
        assertThat(b.networkMaxPlayers()).isEqualTo(500);
    }

    @Test
    void playersArePublishedToRegistryAndEvents() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        controller.connection().createDispatcher(m -> events.add(m.getSubject())).subscribe(BridgeSubjects.playerEvents("prod"));
        KsmcBridge b = bridge("proxy-1", "proxy", new FakePlatform());

        b.playerLogin("uuid-1", "Steve", 770);
        b.playerSwitch("uuid-1", "Steve", "lobby-1");
        await(() -> controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).get("uuid-1") != null);
        PlayerInfo p = Wire.fromJson(controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).get("uuid-1").valueAsString(),
                PlayerInfo.class);
        assertThat(p.server).isEqualTo("lobby-1");
        assertThat(p.proxy).isEqualTo("proxy-1");

        b.playerDisconnect("uuid-1");
        await(() -> events.size() == 3);
        assertThat(events).containsExactly("ksmc.prod.pev.login", "ksmc.prod.pev.switch", "ksmc.prod.pev.disconnect");
        assertThat(controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).get("uuid-1")).isNull();
    }

    static ServiceInfo proxySvc(String name) {
        ServiceInfo s = RouterTest.svc(name, "proxy", 0, false, 0);
        s.environment = "proxy";
        return s;
    }

    @Test
    void playerHasASingleSessionAcrossProxies() throws Exception {
        publishService(proxySvc("proxy-1"));
        publishService(proxySvc("proxy-2"));
        KsmcBridge p1 = bridge("proxy-1", "proxy", new FakePlatform());
        KsmcBridge p2 = bridge("proxy-2", "proxy", new FakePlatform());
        await(() -> p1.registry().get("proxy-2") != null && p2.registry().get("proxy-1") != null);

        assertThat(p1.admitPlayer("uuid-1", "Steve", 770)).isNull();
        p1.playerLogin("uuid-1", "Steve", 770);
        assertThat(p2.admitPlayer("uuid-1", "Steve", 770)).isEqualTo("Tu es déjà connecté au réseau.");
        p1.playerDisconnect("uuid-1");
        assertThat(p2.admitPlayer("uuid-1", "Steve", 770)).isNull();
    }

    @Test
    void replacePolicyKicksTheOldSession() throws Exception {
        publishService(proxySvc("proxy-1"));
        publishService(proxySvc("proxy-2"));
        FakePlatform old = new FakePlatform();
        KsmcBridge p1 = bridge("proxy-1", "proxy", old);
        KsmcBridge p2 = bridge("proxy-2", "proxy", new FakePlatform(), "ksmc.duplicate-login", "replace");
        await(() -> p2.registry().get("proxy-1") != null);
        p1.admitPlayer("uuid-1", "Steve", 770);
        p1.playerLogin("uuid-1", "Steve", 770);

        assertThat(p2.admitPlayer("uuid-1", "Steve", 770)).isNull();
        assertThat(old.calls).contains("kick uuid-1 Tu t'es connecté depuis un autre endroit.");
        PlayerInfo p = Wire.fromJson(controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).get("uuid-1").valueAsString(),
                PlayerInfo.class);
        assertThat(p.proxy).isEqualTo("proxy-2");

        // l'ancien proxy n'a pas vu l'expulsion (FakePlatform ne déconnecte pas) : son rafraîchissement l'applique
        // sans écraser la session reprise
        p1.refreshPlayers();
        assertThat(old.calls.stream().filter(c -> c.startsWith("kick uuid-1")).count()).isEqualTo(2);
        p1.playerDisconnect("uuid-1");
        assertThat(controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).get("uuid-1")).isNotNull();
    }

    @Test
    void broadcastReachesPlayersOfEveryProxy() throws Exception {
        FakePlatform a = new FakePlatform();
        FakePlatform b = new FakePlatform();
        a.players.add(new Platform.OnlinePlayer("uuid-1", "Steve", "lobby-1", 770));
        b.players.add(new Platform.OnlinePlayer("uuid-2", "Alex", "lobby-1", 770));
        KsmcBridge p1 = bridge("proxy-1", "proxy", a);
        bridge("proxy-2", "proxy", b);
        FakePlatform server = new FakePlatform();
        server.players.add(new Platform.OnlinePlayer("uuid-3", "Herobrine", null, 770));
        bridge("game-1", "server", server);

        p1.broadcast("&cRedémarrage");
        await(() -> a.calls.contains("message uuid-1 &cRedémarrage") && b.calls.contains("message uuid-2 &cRedémarrage"));
        assertThat(server.calls).noneMatch(c -> c.startsWith("message"));
    }

    @Test
    void serverReportsStateAndExecutesActions() throws Exception {
        List<ServiceReport> reports = new CopyOnWriteArrayList<>();
        controller.connection().createDispatcher(m -> reports.add(
                Wire.fromJson(new String(m.getData(), StandardCharsets.UTF_8), ServiceReport.class)))
                .subscribe(BridgeSubjects.reports("prod"));
        FakePlatform server = new FakePlatform();
        KsmcBridge b = bridge("game-1", "server", server);

        b.setState("INGAME");
        b.setProperty("map", "Desert");
        await(() -> reports.stream().anyMatch(r -> "INGAME".equals(r.state) && "Desert".equals(r.properties.get("map"))));
        assertThat(reports.get(reports.size() - 1).tps).isEqualTo(19.9);
        assertThat(reports.get(reports.size() - 1).heapUsedBytes).isPositive();
        assertThat(reports.get(reports.size() - 1).gcCount).isNotNull();

        PlayerAction kick = PlayerAction.of(PlayerAction.Type.KICK, "Steve");
        kick.text = "Bye";
        Message reply = controller.connection().request(BridgeSubjects.playerRpc("prod", "game-1"),
                Wire.toJson(kick).getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3));
        assertThat(new String(reply.getData(), StandardCharsets.UTF_8)).isEqualTo("ok");
        assertThat(server.calls).contains("kick Steve Bye");

        PlayerAction unknown = PlayerAction.of(PlayerAction.Type.KICK, "Nobody");
        reply = controller.connection().request(BridgeSubjects.playerRpc("prod", "game-1"),
                Wire.toJson(unknown).getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3));
        assertThat(new String(reply.getData(), StandardCharsets.UTF_8)).startsWith("error:");
    }
}
