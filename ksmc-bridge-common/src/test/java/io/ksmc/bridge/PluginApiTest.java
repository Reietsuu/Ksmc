package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.plugin.CloudEventInfo;
import io.ksmc.plugin.Ksmc;
import io.ksmc.plugin.KsmcPluginApi;
import io.ksmc.plugin.Message;
import io.ksmc.plugin.ServiceQuery;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.bridge.ServiceReport;
import io.ksmc.wire.manifest.ServiceManifest;
import io.ksmc.wire.messaging.ChannelMessage;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.Identity;
import io.ksmc.wire.messaging.Target;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

class PluginApiTest {
    static GenericContainer<?> nats;
    static String url;
    NatsMessaging controller;
    KsmcBridge bridge;
    /** Exécutions marquées comme « thread principal » par la plateforme de test. */
    final List<String> mainThreadRuns = new CopyOnWriteArrayList<>();

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
        controller.ensureInfrastructure("shared");
        controller.ensureBucket(BridgeSubjects.SERVICES_BUCKET, Duration.ofMinutes(1));
        controller.ensureBucket(BridgeSubjects.PLAYERS_BUCKET, Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
        controller.close();
    }

    KsmcPluginApi startBridge() {
        FakePlatform platform = new FakePlatform();
        platform.main = r -> {
            mainThreadRuns.add(Thread.currentThread().getName());
            r.run();
        };
        bridge = new KsmcBridge(new BridgeEnv("prod", "bedwars-1", "bedwars", "server", url, null, null,
                new ServiceManifest()), platform);
        bridge.start();
        // Disponible dès l'activation du bridge, avant la connexion à NATS.
        return Ksmc.get();
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

    static ServiceInfo svc(String name, String task, String state) {
        ServiceInfo s = new ServiceInfo();
        s.name = name;
        s.task = task;
        s.environment = "server";
        s.phase = "Running";
        s.host = "10.0.0.1";
        s.state = state;
        s.onlinePlayers = 3;
        return s;
    }

    void publish(ServiceInfo s) {
        controller.keyValue(BridgeSubjects.SERVICES_BUCKET).put(s.name, Wire.toJson(s).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void entryPointIsAvailableOnlyWhileTheBridgeRuns() {
        KsmcPluginApi api = startBridge();
        assertThat(api.currentService().name()).isEqualTo("bedwars-1");
        assertThat(api.currentService().task()).isEqualTo("bedwars");

        bridge.close();
        bridge = null;
        assertThat(Ksmc.isAvailable()).isFalse();
        assertThatThrownBy(Ksmc::get).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void servicesCanBeQueriedByTaskAndState() throws Exception {
        publish(svc("bedwars-2", "bedwars", "LOBBY"));
        publish(svc("bedwars-3", "bedwars", "INGAME"));
        publish(svc("lobby-1", "lobby", null));
        KsmcPluginApi api = startBridge();
        await(() -> api.services().size() == 3);

        assertThat(api.services(ServiceQuery.task("bedwars").state("LOBBY")))
                .extracting(s -> s.name() + "@" + s.host() + ":" + s.onlinePlayers()).containsExactly("bedwars-2@10.0.0.1:3");
        assertThat(api.tasks()).extracting(t -> t.name() + "=" + t.services() + "/" + t.onlinePlayers())
                .containsExactly("bedwars=2/6", "lobby=1/3");
        assertThat(api.service("lobby-1").isRunning()).isTrue();
    }

    @Test
    void stateAndPropertiesAreReportedToTheController() throws Exception {
        List<ServiceReport> reports = new CopyOnWriteArrayList<>();
        controller.connection().createDispatcher(m -> reports.add(Wire.fromJson(
                new String(m.getData(), StandardCharsets.UTF_8), ServiceReport.class))).subscribe(BridgeSubjects.reports("prod"));
        KsmcPluginApi api = startBridge();
        await(api::isConnected);

        api.state().setState("LOBBY");
        api.state().setProperty("map", "Desert");
        api.state().setMotd("Bedwars !");

        await(() -> reports.stream().anyMatch(r -> "LOBBY".equals(r.state) && "Desert".equals(r.properties.get("map"))
                && "Bedwars !".equals(r.motd)));
        assertThat(api.state().properties()).containsEntry("map", "Desert");
    }

    @Test
    void channelSubscriptionMadeBeforeConnectionReceivesAndReplies() throws Exception {
        KsmcPluginApi api = startBridge();
        List<Message> received = new CopyOnWriteArrayList<>();
        api.channels().subscribe("party", m -> {
            received.add(m);
            return m.reply("pong", "{\"size\":4}");
        });
        await(api::isConnected);

        ChannelMessage reply = controller.channels().request(ChannelMessage.of("party", "ping")
                .target(Target.service("bedwars-1")), 3000).get(5, TimeUnit.SECONDS);

        assertThat(reply.message).isEqualTo("pong");
        assertThat(reply.data.getAsJsonObject().get("size").getAsInt()).isEqualTo(4);
        assertThat(received).singleElement().satisfies(m -> assertThat(m.message()).isEqualTo("ping"));
    }

    @Test
    void requestsAreAsynchronousAndCompleteOnTheMainThread() throws Exception {
        controller.channels().subscribe("stats", m -> m.reply("42"));
        KsmcPluginApi api = startBridge();
        await(api::isConnected);
        mainThreadRuns.clear();

        Message reply = api.channels().request("controller", "stats", "players", null, 3000).get(5, TimeUnit.SECONDS);

        assertThat(reply.message()).isEqualTo("42");
        assertThat(mainThreadRuns).isNotEmpty();
    }

    @Test
    void eventsKeyValueAndPlayers() throws Exception {
        KsmcPluginApi api = startBridge();
        List<CloudEventInfo> events = new CopyOnWriteArrayList<>();
        api.events().subscribe("service.*", events::add);
        List<String> watched = new CopyOnWriteArrayList<>();
        api.keyValue("shared").watch(">", (k, v) -> watched.add(k + "=" + v));
        await(api::isConnected);
        Thread.sleep(200);

        controller.events().publish(CloudEvent.of("service.started", "lobby-9"));
        await(() -> events.stream().anyMatch(e -> e.subject().equals("lobby-9")));

        api.keyValue("shared").put("season", "3").get(5, TimeUnit.SECONDS);
        assertThat(api.keyValue("shared").get("season").get(5, TimeUnit.SECONDS)).isEqualTo("3");
        await(() -> watched.contains("season=3"));

        PlayerInfo steve = new PlayerInfo();
        steve.uuid = "uuid-1";
        steve.name = "Steve";
        steve.proxy = "proxy-1";
        steve.server = "bedwars-1";
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).put("uuid-1", Wire.toJson(steve).getBytes(StandardCharsets.UTF_8));
        assertThat(api.players().get(5, TimeUnit.SECONDS)).extracting(p -> p.name() + "@" + p.server())
                .containsExactly("Steve@bedwars-1");
        assertThat(api.player("steve").get(5, TimeUnit.SECONDS).uuid()).isEqualTo("uuid-1");
        assertThat(api.player("nobody").get(5, TimeUnit.SECONDS)).isNull();
    }
}
