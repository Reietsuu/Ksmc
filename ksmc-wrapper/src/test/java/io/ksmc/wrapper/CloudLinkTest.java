package io.ksmc.wrapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.messaging.nats.Subjects;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

class CloudLinkTest {
    static GenericContainer<?> nats;
    static String url;

    @BeforeAll
    static void start() {
        nats = new GenericContainer<>("nats:2.11-alpine").withExposedPorts(4222);
        nats.start();
        url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
    }

    @AfterAll
    static void stop() {
        nats.stop();
    }

    @Test
    void consoleCommandsAreForwardedAndAcknowledged() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        WrapperEnv env = new WrapperEnv("http://x", null, "prod", "lobby-1", null, 0, null, "lobby", "server", url, null, null);
        try (CloudLink link = new CloudLink(env, received::add)) {
            link.connect();
            assertThat(link.isConnected()).isTrue();

            try (Connection controller = Nats.connect(url)) {
                Message reply = controller.request(Subjects.command("prod", "lobby-1"),
                        "say hello".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3));

                assertThat(new String(reply.getData(), StandardCharsets.UTF_8)).isEqualTo("ok");
                assertThat(received).containsExactly("say hello");
            }
        }
    }

    @Test
    void failedCommandIsReported() throws Exception {
        WrapperEnv env = new WrapperEnv("http://x", null, "prod", "lobby-2", null, 0, null, "lobby", "server", url, null, null);
        try (CloudLink link = new CloudLink(env, c -> {
            throw new java.io.IOException("server is not running");
        })) {
            link.connect();
            try (Connection controller = Nats.connect(url)) {
                Message reply = controller.request(Subjects.command("prod", "lobby-2"), "list".getBytes(StandardCharsets.UTF_8),
                        Duration.ofSeconds(3));
                assertThat(new String(reply.getData(), StandardCharsets.UTF_8)).startsWith("error:");
            }
        }
    }

    @Test
    void bridgeReportsOfThisServiceAreForwarded() throws Exception {
        List<io.ksmc.wire.bridge.ServiceReport> reports = new CopyOnWriteArrayList<>();
        WrapperEnv env = new WrapperEnv("http://x", null, "prod", "lobby-3", null, 0, null, "lobby", "server", url, null, null);
        try (CloudLink link = new CloudLink(env, c -> { }, reports::add)) {
            link.connect();
            try (Connection bridge = Nats.connect(url)) {
                bridge.publish(io.ksmc.wire.bridge.BridgeSubjects.report("prod", "lobby-4"),
                        "{\"service\":\"lobby-4\",\"tps\":12.0}".getBytes(StandardCharsets.UTF_8));
                bridge.publish(io.ksmc.wire.bridge.BridgeSubjects.report("prod", "lobby-3"),
                        "{\"service\":\"lobby-3\",\"tps\":19.5,\"onlinePlayers\":2}".getBytes(StandardCharsets.UTF_8));
                bridge.flush(Duration.ofSeconds(3));
            }
            long deadline = System.currentTimeMillis() + 3000;
            while (reports.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).tps).isEqualTo(19.5);
            assertThat(reports.get(0).onlinePlayers).isEqualTo(2);
        }
    }
}
