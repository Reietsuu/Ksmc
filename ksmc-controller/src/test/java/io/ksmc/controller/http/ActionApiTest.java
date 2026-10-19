package io.ksmc.controller.http;

import static io.ksmc.controller.http.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.controller.bridge.PlayerService;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class ActionApiTest {
    KubernetesClient client;
    ApiTestServer api;
    final List<String> sent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(null, "test", java.time.Clock.systemUTC(), List::of);
        ActionApi.Commands commands = (service, command) -> {
            sent.add(service + ":" + command);
            return service.equals("down-1") ? "error: server is not running" : "ok";
        };
        api = new ApiTestServer(client, List.of(new ActionApi(client, "test", commands, new PlayerService(() -> null),
                audit)), audit);
        client.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: game, namespace: test}
                spec: {environment: server, software: {name: paper}, minServiceCount: 1, maxServiceCount: 4}
                """, ServiceTask.class)).create();
        service("game-1");
        service("down-1");
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    void service(String name) {
        CloudService svc = new CloudService();
        svc.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("test")
                .addToLabels(Ksmc.LABEL_TASK, name.replaceAll("-\\d+$", "")).build());
        svc.setSpec(new CloudServiceSpec());
        client.resource(svc).create();
    }

    ServiceTask task() {
        return client.resources(ServiceTask.class).inNamespace("test").withName("game").get();
    }

    @Test
    void startingServicesReturnsTheirNamesAndIsCapped() {
        HttpResponse<String> r = api.call("POST", "/api/v1/tasks/game/start", api.operator, Map.of("count", 2));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        assertThat(json(r).get("services").toString()).isEqualTo("[\"game-2\",\"game-3\"]");
        assertThat(task().getSpec().extraServices).isEqualTo(2);

        // 1 (min) + 2 (manuels) : il ne reste qu'une place sous maxServiceCount = 4.
        HttpResponse<String> capped = api.call("POST", "/api/v1/tasks/game/start", api.operator, Map.of("count", 5));
        assertThat(json(capped).get("started").asInt()).isEqualTo(1);
        assertThat(api.call("POST", "/api/v1/tasks/game/start", api.operator, Map.of("count", 1)).statusCode())
                .isEqualTo(409);
        assertThat(api.call("POST", "/api/v1/tasks/game/start", api.viewer, Map.of("count", 1)).statusCode())
                .isEqualTo(403);
        assertThat(api.call("POST", "/api/v1/tasks/absent/start", api.operator, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void maintenanceStopStartAndRestart() {
        assertThat(api.call("POST", "/api/v1/tasks/game/maintenance", api.operator, Map.of("enabled", true)).statusCode())
                .isEqualTo(200);
        assertThat(task().getSpec().maintenance).isTrue();

        assertThat(api.call("POST", "/api/v1/services/game-1/stop", api.operator, null).statusCode()).isEqualTo(202);
        CloudService svc = client.resources(CloudService.class).inNamespace("test").withName("game-1").get();
        assertThat(svc.getSpec().desiredState).isEqualTo(CloudServiceSpec.DesiredState.Stopped);
        api.call("POST", "/api/v1/services/game-1/start", api.operator, null);
        svc = client.resources(CloudService.class).inNamespace("test").withName("game-1").get();
        assertThat(svc.getSpec().desiredState).isEqualTo(CloudServiceSpec.DesiredState.Running);

        client.pods().inNamespace("test").resource(new PodBuilder().withNewMetadata().withName("game-1").endMetadata()
                .build()).create();
        assertThat(api.call("POST", "/api/v1/services/game-1/restart", api.operator, null).statusCode()).isEqualTo(202);
        assertThat(client.pods().inNamespace("test").withName("game-1").get()).isNull();
        assertThat(api.audit.query("op", "services.", null, 10)).extracting(e -> e.action())
                .containsExactly("services.restart", "services.start", "services.stop");
    }

    @Test
    void commandsAreSentButControlPrefixIsReserved() {
        HttpResponse<String> r = api.call("POST", "/api/v1/services/game-1/command", api.operator, Map.of("command", "list"));
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(sent).containsExactly("game-1:list");

        assertThat(api.call("POST", "/api/v1/services/game-1/command", api.operator,
                Map.of("command", "ksmc:deploy x")).statusCode()).isEqualTo(400);
        HttpResponse<String> down = api.call("POST", "/api/v1/services/down-1/command", api.operator,
                Map.of("command", "list"));
        assertThat(down.statusCode()).isEqualTo(409);
        assertThat(json(down).get("code").asText()).isEqualTo("CommandRejected");
        assertThat(api.call("POST", "/api/v1/services/game-1/command", api.viewer, Map.of("command", "stop"))
                .statusCode()).isEqualTo(403);
    }

    @Test
    void deployNeedsTemplateWriteAndValidatesThePath() {
        assertThat(api.call("POST", "/api/v1/services/game-1/deploy", api.operator, Map.of("template", "game/backup"))
                .statusCode()).isEqualTo(202);
        assertThat(sent).containsExactly("game-1:ksmc:deploy game/backup");
        assertThat(api.call("POST", "/api/v1/services/game-1/deploy", api.operator, Map.of("template", "../etc"))
                .statusCode()).isEqualTo(400);
    }

    @Test
    void playerActionsReportUnavailableMessaging() {
        HttpResponse<String> r = api.call("POST", "/api/v1/players/Steve/send", api.operator, Map.of("task", "lobby"));
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(api.call("POST", "/api/v1/players/Steve/send", api.operator, Map.of()).statusCode()).isEqualTo(400);
        assertThat(api.call("POST", "/api/v1/players/Steve/kick", api.viewer, Map.of()).statusCode()).isEqualTo(403);
    }
}
