package io.ksmc.messaging.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ksmc.wire.messaging.ChannelMessage;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Identity;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingException;
import io.ksmc.wire.messaging.Target;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

class NatsMessagingTest {
    static GenericContainer<?> nats;
    static String url;
    final List<NatsMessaging> open = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startNats() {
        nats = new GenericContainer<>("nats:2.11-alpine").withCommand("-js").withExposedPorts(4222);
        nats.start();
        url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
    }

    @AfterAll
    static void stopNats() {
        nats.stop();
    }

    @AfterEach
    void close() {
        open.forEach(NatsMessaging::close);
    }

    NatsMessaging connect(Identity id) throws Exception {
        NatsMessaging m = NatsMessaging.connect(id, url, null, null);
        open.add(m);
        return m;
    }

    NatsMessaging controller(String ns) throws Exception {
        return connect(Identity.controller(ns));
    }

    NatsMessaging service(String ns, String name, String task, String env) throws Exception {
        return connect(Identity.service(ns, name, task, env));
    }

    static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timeout");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void broadcastReachesEveryComponentWithSender() throws Exception {
        NatsMessaging lobby = service("prod", "lobby-1", "lobby", "server");
        NatsMessaging proxy = service("prod", "proxy-1", "proxy", "proxy");
        NatsMessaging ctl = controller("prod");
        List<String> received = new CopyOnWriteArrayList<>();
        proxy.channels().subscribe("chat", m -> {
            received.add("proxy:" + m.sender);
            return null;
        });
        ctl.channels().subscribe("chat", m -> {
            received.add("controller:" + m.sender);
            return null;
        });
        Thread.sleep(100);

        lobby.channels().send(ChannelMessage.of("chat", "broadcast").data(java.util.Map.of("text", "hi")));

        await(() -> received.size() == 2);
        assertThat(received).containsExactlyInAnyOrder("proxy:service/lobby-1", "controller:service/lobby-1");
    }

    @Test
    void taskTargetOnlyReachesThatTask() throws Exception {
        NatsMessaging bedwars = service("prod", "bedwars-1", "bedwars", "server");
        NatsMessaging lobby = service("prod", "lobby-1", "lobby", "server");
        NatsMessaging ctl = controller("prod");
        List<String> received = new CopyOnWriteArrayList<>();
        bedwars.channels().subscribe("game", m -> {
            received.add("bedwars");
            return null;
        });
        lobby.channels().subscribe("game", m -> {
            received.add("lobby");
            return null;
        });
        Thread.sleep(100);

        ctl.channels().send(ChannelMessage.of("game", "start").target(Target.task("bedwars")));
        ctl.channels().send(ChannelMessage.of("game", "ping").target(Target.environment("server")));

        await(() -> received.size() == 3);
        assertThat(received).containsExactlyInAnyOrder("bedwars", "bedwars", "lobby");
    }

    @Test
    void namespacesAreIsolated() throws Exception {
        NatsMessaging prod = service("prod", "lobby-1", "lobby", "server");
        NatsMessaging test = controller("test");
        List<String> received = new CopyOnWriteArrayList<>();
        prod.channels().subscribe("chat", m -> {
            received.add("x");
            return null;
        });
        Thread.sleep(100);

        test.channels().send(ChannelMessage.of("chat", "hello"));
        Thread.sleep(300);

        assertThat(received).isEmpty();
    }

    @Test
    void requestGetsReplyOrFailsFast() throws Exception {
        NatsMessaging shop = service("prod", "shop-1", "shop", "server");
        NatsMessaging ctl = controller("prod");
        shop.channels().subscribe("shop", m -> m.reply("price").data(java.util.Map.of("value", 42)));
        Thread.sleep(100);

        ChannelMessage reply = ctl.channels().request(ChannelMessage.of("shop", "price?").target(Target.service("shop-1")), 2000)
                .get(3, TimeUnit.SECONDS);
        assertThat(reply.message).isEqualTo("price");
        assertThat(reply.sender.name).isEqualTo("shop-1");
        assertThat(reply.data.getAsJsonObject().get("value").getAsInt()).isEqualTo(42);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> ctl.channels().request(ChannelMessage.of("nobody", "x"), 1000).get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(MessagingException.class);
        assertThat(System.currentTimeMillis() - start).isLessThan(2000);
    }

    @Test
    void eventsAreFilteredAndReplayable() throws Exception {
        NatsMessaging ctl = controller("prod");
        ctl.ensureInfrastructure();
        ctl.events().publish(event("service.ready", "lobby-1", "lobby", "server"));
        ctl.events().publish(event("service.ready", "proxy-1", "proxy", "proxy"));
        ctl.events().publish(event("task.updated", "lobby", "lobby", "server"));

        List<CloudEvent> replayed = new CopyOnWriteArrayList<>();
        ctl.events().subscribe(EventFilter.type("service.*").environment("server").sinceSequence(1), replayed::add);
        await(() -> replayed.size() == 1);
        assertThat(replayed.get(0).subject).isEqualTo("lobby-1");
        assertThat(replayed.get(0).sequence).isPositive();
        assertThat(replayed.get(0).namespace).isEqualTo("prod");

        List<CloudEvent> live = new CopyOnWriteArrayList<>();
        ctl.events().subscribe(EventFilter.type(">"), live::add);
        Thread.sleep(200);
        ctl.events().publish(event("module.stats.record", "x", null, null));
        await(() -> live.size() == 1);
        assertThat(live.get(0).type).isEqualTo("module.stats.record");
    }

    @Test
    void keyValueCompareAndSetAndWatch() throws Exception {
        NatsMessaging ctl = controller("prod");
        ctl.ensureInfrastructure("counters");
        KeyValueApi kv = service("prod", "game-1", "game", "server").keyValue("counters");
        KeyValueApi kv2 = service("prod", "game-2", "game", "server").keyValue("counters");
        List<String> changes = new CopyOnWriteArrayList<>();
        kv2.watch(">", e -> changes.add(e.key + "=" + e.valueAsString()));

        long rev = kv.update("games", bytes("1"), 0);
        assertThat(kv2.get("games").valueAsString()).isEqualTo("1");
        kv.update("games", bytes("2"), rev);
        assertThatThrownBy(() -> kv2.update("games", bytes("3"), rev))
                .isInstanceOf(MessagingException.class).hasMessageStartingWith("Conflict");

        await(() -> changes.size() >= 2);
        assertThat(changes).contains("games=1", "games=2");
        kv.delete("games");
        assertThat(kv.get("games")).isNull();
    }

    @Test
    void bucketWithTtlExpiresEntries() throws Exception {
        NatsMessaging ctl = controller("prod");
        ctl.ensureBucket("players", Duration.ofSeconds(1));
        KeyValueApi kv = ctl.keyValue("players");
        kv.put("steve", bytes("lobby-1"));
        assertThat(kv.get("steve")).isNotNull();

        Thread.sleep(2500);
        assertThat(kv.get("steve")).isNull();
    }

    @Test
    void invalidChannelNamesAreRejected() throws Exception {
        NatsMessaging ctl = controller("prod");
        assertThatThrownBy(() -> ctl.channels().send(ChannelMessage.of("bad.channel", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static CloudEvent event(String type, String subject, String task, String env) {
        CloudEvent e = CloudEvent.of(type, subject);
        e.task = task;
        e.environment = env;
        return e;
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
