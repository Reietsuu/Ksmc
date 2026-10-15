package io.ksmc.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ksmc.wire.messaging.ChannelMessage;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.Sender;
import io.ksmc.wire.messaging.Target;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireCompatibilityTest {

    record Broadcast(String text, int priority) {
    }

    @Test
    void channelMessageRoundTripWithTypedData() {
        ChannelMessage m = ChannelMessage.of("chat", "broadcast").target(Target.task("bedwars"))
                .data(Map.of("text", "hello", "priority", 2));
        m.sender = new Sender(Sender.Type.SERVICE, "lobby-1");

        ChannelMessage back = Wire.fromJson(Wire.toJson(m), ChannelMessage.class);

        assertThat(back.channel).isEqualTo("chat");
        assertThat(back.target.type).isEqualTo(Target.Type.TASK);
        assertThat(back.sender.toString()).isEqualTo("service/lobby-1");
        assertThat(back.data(Broadcast.class)).isEqualTo(new Broadcast("hello", 2));
        assertThat(back.v).isEqualTo(1);
    }

    @Test
    void unknownFieldsAreIgnored() {
        String json = "{\"v\":2,\"channel\":\"chat\",\"message\":\"x\",\"futureField\":{\"a\":1},\"target\":{\"type\":\"ALL\"}}";
        ChannelMessage m = Wire.fromJson(json, ChannelMessage.class);
        assertThat(m.channel).isEqualTo("chat");
        assertThat(m.v).isEqualTo(2);
    }

    @Test
    void eventSequenceIsNotSerialized() {
        CloudEvent e = CloudEvent.of("service.ready", "lobby-1");
        e.sequence = 42;
        assertThat(Wire.toJson(e)).doesNotContain("sequence");
    }

    @Test
    void targetsParse() {
        assertThat(Target.parse("service:lobby-1").toString()).isEqualTo("service:lobby-1");
        assertThat(Target.parse("env:proxy").type).isEqualTo(Target.Type.ENVIRONMENT);
        assertThat(Target.parse("all").type).isEqualTo(Target.Type.ALL);
        assertThatThrownBy(() -> Target.parse("nobody")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replyGoesBackToSender() {
        ChannelMessage m = ChannelMessage.of("shop", "buy");
        m.sender = new Sender(Sender.Type.SERVICE, "game-3");
        assertThat(m.reply("ok").target.toString()).isEqualTo("service:game-3");
    }
}
