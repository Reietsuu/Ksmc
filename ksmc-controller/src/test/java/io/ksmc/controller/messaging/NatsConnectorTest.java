package io.ksmc.controller.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ksmc.controller.ControllerConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NatsConnectorTest {

    @Test
    void listenerRegisteredAfterConnectIsRejected() throws Exception {
        NatsConnector nats = new NatsConnector(ControllerConfig.fromEnv(Map.of("KSMC_RUNTIME_TAG", "0.1.0",
                "KSMC_NATS_URL", "nats://127.0.0.1:1")));
        nats.onReady(m -> { });
        nats.connectAsync();
        try {
            assertThrows(IllegalStateException.class, () -> nats.onReady(m -> { }));
        } finally {
            nats.close();
        }
    }
}
