package io.ksmc.controller.http;

import static io.ksmc.controller.http.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.controller.bridge.PlayerService;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class ResourceApiTest {
    KubernetesClient client;
    ApiTestServer api;

    static final Map<String, Object> LOBBY = Map.of("metadata", Map.of("name", "lobby", "labels", Map.of("tier", "hub")),
            "spec", Map.of("environment", "server", "software", Map.of("name", "paper", "version", "latest"),
                    "minServiceCount", 2));

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(null, "test", java.time.Clock.systemUTC(), List::of);
        api = new ApiTestServer(client, List.of(new ResourceApi(client, "test",
                new PlayerService(() -> null), audit)), audit);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void taskCreatedByRestIsAKubernetesResource() {
        HttpResponse<String> r = api.call("POST", "/api/v1/tasks", api.admin, LOBBY);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("Location")).hasValue("/api/v1/tasks/lobby");
        assertThat(r.headers().firstValue("ETag")).isPresent();

        ServiceTask task = client.resources(ServiceTask.class).inNamespace("test").withName("lobby").get();
        assertThat(task.getSpec().minServiceCount).isEqualTo(2);
        assertThat(task.getMetadata().getLabels()).containsEntry("tier", "hub");
        assertThat(api.call("POST", "/api/v1/tasks", api.admin, LOBBY).statusCode()).isEqualTo(409);
        // Les tentatives refusées sont aussi auditées.
        assertThat(api.audit.query("root", "tasks.create", "lobby", 5)).extracting(e -> e.result())
                .containsExactly("error", "ok");
    }

    @Test
    void kubectlChangesAreVisibleImmediately() {
        api.call("POST", "/api/v1/tasks", api.admin, LOBBY);
        client.resources(ServiceTask.class).inNamespace("test").withName("lobby").edit(t -> {
            t.getSpec().minServiceCount = 5;
            return t;
        });

        HttpResponse<String> r = api.call("GET", "/api/v1/tasks/lobby", api.viewer, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).at("/spec/minServiceCount").asInt()).isEqualTo(5);
        assertThat(json(r).at("/metadata/managedFields").isMissingNode()).isTrue();
        assertThat(json(api.call("GET", "/api/v1/tasks", api.viewer, null)).get("items")).hasSize(1);
    }

    @Test
    void staleETagGives409Conflict() {
        String etag = api.call("POST", "/api/v1/tasks", api.admin, LOBBY).headers().firstValue("ETag").orElseThrow();
        Map<String, Object> three = Map.of("spec", Map.of("environment", "server",
                "software", Map.of("name", "paper", "version", "latest"), "minServiceCount", 3));

        HttpResponse<String> first = api.call("PUT", "/api/v1/tasks/lobby", api.operator, three, Map.of("If-Match", etag));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        HttpResponse<String> second = api.call("PUT", "/api/v1/tasks/lobby", api.operator, three, Map.of("If-Match", etag));
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json(second).get("code").asText()).isEqualTo("Conflict");

        HttpResponse<String> stalePatch = api.call("PATCH", "/api/v1/tasks/lobby", api.operator,
                Map.of("spec", Map.of("minServiceCount", 4)), Map.of("If-Match", etag));
        assertThat(stalePatch.statusCode()).isEqualTo(409);
        assertThat(api.call("DELETE", "/api/v1/tasks/lobby", api.operator, null, Map.of("If-Match", etag)).statusCode())
                .isEqualTo(409);
    }

    @Test
    void patchMergesAndKeepsStatusOwnedByTheController() {
        api.call("POST", "/api/v1/tasks", api.admin, LOBBY);
        client.resources(ServiceTask.class).inNamespace("test").withName("lobby").editStatus(t -> {
            t.setStatus(new io.ksmc.api.model.ServiceTaskStatus());
            t.getStatus().onlinePlayers = 7;
            return t;
        });

        HttpResponse<String> r = api.call("PATCH", "/api/v1/tasks/lobby", api.operator,
                Map.of("spec", Map.of("maxServiceCount", 10), "status", Map.of("onlinePlayers", 0)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        ServiceTask task = client.resources(ServiceTask.class).inNamespace("test").withName("lobby").get();
        assertThat(task.getSpec().minServiceCount).isEqualTo(2);
        assertThat(task.getSpec().maxServiceCount).isEqualTo(10);
        assertThat(task.getStatus().onlinePlayers).isEqualTo(7);
    }

    @Test
    void viewerCannotDeleteAServiceAndErrorsAreUniform() {
        CloudService svc = new CloudService();
        svc.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("lobby-1").withNamespace("test")
                .build());
        svc.setSpec(new io.ksmc.api.model.CloudServiceSpec());
        client.resource(svc).create();

        HttpResponse<String> r = api.call("DELETE", "/api/v1/services/lobby-1", api.viewer, null);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("code").asText()).isEqualTo("Forbidden");
        assertThat(json(r).at("/details/permission").asText()).isEqualTo(Permissions.SERVICE_WRITE);

        assertThat(json(api.call("GET", "/api/v1/services/absent", api.viewer, null)).get("code").asText())
                .isEqualTo("NotFound");
        assertThat(api.call("POST", "/api/v1/tasks", api.admin, "{pas du json").statusCode()).isEqualTo(400);
        assertThat(api.call("POST", "/api/v1/tasks", api.admin, Map.of("spec", Map.of())).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/v1/tasks?limit=0", api.viewer, null).statusCode()).isEqualTo(400);
        assertThat(api.call("DELETE", "/api/v1/services/lobby-1", api.operator, null).statusCode()).isEqualTo(204);
    }

    @Test
    void softwareAndModulesNeedAdmin() {
        Map<String, Object> mod = Map.of("metadata", Map.of("name", "hello"),
                "spec", Map.of("source", Map.of("store", "modules/hello.jar")));
        assertThat(api.call("POST", "/api/v1/modules", api.operator, mod).statusCode()).isEqualTo(403);
        assertThat(api.call("POST", "/api/v1/modules", api.admin, mod).statusCode()).isEqualTo(201);
        assertThat(api.call("GET", "/api/v1/modules", api.viewer, null).statusCode()).isEqualTo(200);
    }

    @Test
    void playersUnavailableWithoutMessaging() {
        HttpResponse<String> r = api.call("GET", "/api/v1/players", api.viewer, null);
        assertThat(r.statusCode()).isEqualTo(503);
    }
}
