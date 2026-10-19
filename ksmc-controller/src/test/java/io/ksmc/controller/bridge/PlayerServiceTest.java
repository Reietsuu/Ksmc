package io.ksmc.controller.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerAction;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.messaging.Identity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

class PlayerServiceTest {
    static GenericContainer<?> nats;
    static NatsMessaging controller;

    @BeforeAll
    static void start() throws Exception {
        nats = new GenericContainer<>("nats:2.11-alpine").withCommand("-js").withExposedPorts(4222);
        nats.start();
        controller = NatsMessaging.connect(Identity.controller("prod"),
                "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222), null, null);
        controller.ensureBucket(BridgeSubjects.PLAYERS_BUCKET, Duration.ofSeconds(30));
    }

    @AfterAll
    static void stop() {
        controller.close();
        nats.stop();
    }

    @Test
    void listsFindsAndActsThroughTheHostingProxy() throws Exception {
        PlayerService players = new PlayerService(() -> controller);
        assertThat(players.list()).isEmpty();

        PlayerInfo steve = new PlayerInfo();
        steve.uuid = "uuid-steve";
        steve.name = "Steve";
        steve.proxy = "proxy-1";
        steve.server = "lobby-1";
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).put(steve.uuid, Wire.toJson(steve).getBytes(StandardCharsets.UTF_8));
        // Faux proxy : répond aux actions.
        controller.connection().createDispatcher(msg -> {
            PlayerAction a = Wire.fromJson(new String(msg.getData(), StandardCharsets.UTF_8), PlayerAction.class);
            controller.connection().publish(msg.getReplyTo(), ("ok " + a.type + " " + a.player + " " + a.targetTask)
                    .getBytes(StandardCharsets.UTF_8));
        }).subscribe(BridgeSubjects.playerRpc("prod", "proxy-1"));

        assertThat(players.list()).extracting(p -> p.name).containsExactly("Steve");
        assertThat(players.find("steve")).get().extracting(p -> p.server).isEqualTo("lobby-1");

        PlayerAction send = PlayerAction.of(PlayerAction.Type.SEND, "Steve");
        send.targetTask = "bedwars";
        assertThat(players.act(send)).isEqualTo("ok SEND uuid-steve bedwars");
        assertThat(players.act(PlayerAction.of(PlayerAction.Type.KICK, "Nobody"))).startsWith("error: player not online");
    }
}
