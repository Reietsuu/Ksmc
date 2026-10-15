package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.messaging.nats.Subjects;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Identity;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scénarios du change p04-messaging, via un port-forward vers le NATS du cluster. */
class MessagingE2eTest {
    static LocalPortForward forward;
    static String url;

    @BeforeAll
    static void forward() {
        forward = CLIENT.services().inNamespace(NAMESPACE).withName("ksmc-nats").portForward(4222);
        url = "nats://127.0.0.1:" + forward.getLocalPort();
    }

    @AfterAll
    static void close() throws Exception {
        forward.close();
    }

    @AfterEach
    void cleanup() {
        CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withLabel("e2e", "messaging").delete();
    }

    static String password(String key) {
        return new String(Base64.getDecoder().decode(CLIENT.secrets().inNamespace(NAMESPACE).withName("ksmc-nats-auth")
                .get().getData().get(key)), StandardCharsets.UTF_8);
    }

    @Test
    void controllerPublishesServiceEventsAndForwardsCommands() throws Exception {
        try (NatsMessaging observer = NatsMessaging.connect(Identity.controller(NAMESPACE), url, "controller",
                password("controller-password"))) {
            List<CloudEvent> ready = new CopyOnWriteArrayList<>();
            observer.events().subscribe(EventFilter.type("service.ready"), e -> {
                if (e.subject.equals("e2e-msg")) {
                    ready.add(e);
                }
            });
            CLIENT.resource(Serialization.unmarshal("""
                    apiVersion: ksmc.io/v1alpha1
                    kind: CloudService
                    metadata: {name: e2e-msg, labels: {e2e: messaging}}
                    spec: {environment: proxy, software: {name: velocity}, memoryMb: 256}
                    """, CloudService.class)).inNamespace(NAMESPACE).create();

            await("événement service.ready", Duration.ofMinutes(6), () -> !ready.isEmpty());
            assertThat(ready.get(0).environment).isEqualTo("proxy");

            // Le wrapper se connecte à NATS juste après le démarrage : on réessaie quelques secondes.
            String[] ack = new String[1];
            await("accusé de commande", Duration.ofSeconds(30), () -> {
                try {
                    Message reply = observer.connection().request(Subjects.command(NAMESPACE, "e2e-msg"),
                            "velocity version".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3));
                    ack[0] = reply == null || reply.isStatusMessage() ? null : new String(reply.getData(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    ack[0] = null;
                }
                return "ok".equals(ack[0]);
            });
            String logs = CLIENT.pods().inNamespace(NAMESPACE).withName("e2e-msg").inContainer("server").getLog();
            assertThat(logs).containsIgnoringCase("velocity");
        }
    }

    @Test
    void servicesCannotPublishCloudEvents() throws Exception {
        try (NatsMessaging service = NatsMessaging.connect(Identity.service(NAMESPACE, "rogue", "x", "server"), url,
                "service", password("service-password"))) {
            assertThatThrownBy(() -> service.connection().jetStream().publish(
                    Subjects.event(NAMESPACE, "service.started"), "{}".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(Exception.class);
        }
    }
}
