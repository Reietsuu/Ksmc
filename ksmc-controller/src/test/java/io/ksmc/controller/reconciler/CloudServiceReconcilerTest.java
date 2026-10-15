package io.ksmc.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.service.ArtifactCache;
import io.ksmc.controller.service.PodFactory;
import io.ksmc.controller.service.ServiceSoftware;
import io.ksmc.controller.software.BuildJobRunner;
import io.ksmc.controller.software.JdkResolverContext;
import io.ksmc.controller.software.SoftwareCatalog;
import io.ksmc.controller.storage.FileArtifactStorage;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@EnableKubernetesMockClient(crud = true)
class CloudServiceReconcilerTest {
    KubernetesClient client;
    final java.util.List<io.ksmc.wire.messaging.CloudEvent> published = new java.util.concurrent.CopyOnWriteArrayList<>();

    @TempDir
    Path tmp;
    HttpServer http;
    FileArtifactStorage storage;
    CloudServiceReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/paper.jar", ex -> {
            byte[] body = "JAR".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        http.start();
        storage = new FileArtifactStorage(tmp);
        ControllerConfig config = ControllerConfig.fromEnv(Map.of("KSMC_RUNTIME_TAG", "0.1.0",
                "KSMC_INTERNAL_URL", "http://ksmc-controller.test.svc:8080"));
        ServiceSoftware software = new ServiceSoftware(client, new SoftwareCatalog(new JdkResolverContext(), Clock.systemUTC()),
                new ArtifactCache(storage, HttpClient.newHttpClient()), new BuildJobRunner(client, storage, config), config);
        reconciler = new CloudServiceReconciler(client, software, new PodFactory(config), new EventRecorder(client),
                published::add, Clock.systemUTC());

        client.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: SoftwareDefinition
                metadata: {name: paper, namespace: test}
                spec:
                  environment: server
                  family: paper
                  resolver: {type: url, params: {url: 'http://127.0.0.1:%d/paper.jar', versions: '1.21.4'}}
                  javaVersions: [{minecraft: "1.20.5+", java: 21}, {minecraft: "1.8-1.16.5", java: 8}]
                """.formatted(http.getAddress().getPort()), SoftwareDefinition.class)).create();
    }

    @AfterEach
    void tearDown() {
        http.stop(0);
    }

    CloudService service(String yamlSpec) {
        return client.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: CloudService
                metadata: {name: lobby-1, namespace: test}
                spec:
                %s
                """.formatted(yamlSpec.indent(2)), CloudService.class)).create();
    }

    CloudService reconcileUntilPodCreated(CloudService svc) throws Exception {
        for (int i = 0; i < 50; i++) {
            svc = reconciler.reconcile(svc, (Pod) null).getResource().orElseThrow();
            if (client.pods().inNamespace("test").withName("lobby-1").get() != null) {
                return svc;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("pod never created, status: " + Serialization.asYaml(svc.getStatus()));
    }

    @Test
    void createsPodWithRuntimeImageProbesAndOwner() throws Exception {
        CloudService svc = service("""
                environment: server
                software: {name: paper, version: latest}
                memoryMb: 1024
                env: {TZ: Europe/Paris}
                podTemplate:
                  spec:
                    nodeSelector: {pool: games}
                    containers:
                    - {name: sidecar, image: busybox}
                """);

        svc = reconcileUntilPodCreated(svc);

        Pod pod = client.pods().inNamespace("test").withName("lobby-1").get();
        var server = pod.getSpec().getContainers().stream().filter(c -> c.getName().equals("server")).findFirst().orElseThrow();
        assertThat(server.getImage()).isEqualTo("ghcr.io/reietsuu/ksmc-runtime:0.1.0-java21");
        assertThat(server.getReadinessProbe().getHttpGet().getPath()).isEqualTo("/ready");
        assertThat(server.getResources().getLimits().get("memory").toString()).isEqualTo("1536Mi");
        assertThat(server.getEnv()).anyMatch(e -> e.getName().equals("TZ") && e.getValue().equals("Europe/Paris"));
        assertThat(pod.getSpec().getInitContainers()).extracting("name").containsExactly("prepare");
        assertThat(pod.getSpec().getContainers()).extracting("name").containsExactly("server", "sidecar");
        assertThat(pod.getSpec().getNodeSelector()).containsEntry("pool", "games");
        assertThat(pod.getMetadata().getLabels()).containsEntry("ksmc.io/service", "lobby-1")
                .containsEntry("ksmc.io/environment", "server");
        assertThat(pod.getMetadata().getOwnerReferences().getFirst().getKind()).isEqualTo("CloudService");
        assertThat(svc.getStatus().phase).isEqualTo(Phase.Preparing);
        assertThat(svc.getStatus().softwareVersion).isEqualTo("1.21.4");
        assertThat(storage.exists("artifacts/url/paper/1.21.4/server.jar")).isTrue();
        assertThat(client.secrets().inNamespace("test").withName("ksmc-forwarding").get()).isNotNull();
    }

    @Test
    void reflectsRunningPodThenStopsOnRequest() throws Exception {
        CloudService svc = reconcileUntilPodCreated(service("environment: server\nsoftware: {name: paper, version: '1.21.4'}"));
        Pod pod = client.pods().inNamespace("test").withName("lobby-1").get();
        pod.setStatus(Serialization.unmarshal("""
                phase: Running
                podIP: 10.0.0.7
                initContainerStatuses: [{name: prepare, state: {terminated: {exitCode: 0}}}]
                containerStatuses: [{name: server, ready: true, state: {running: {}}}]
                """, io.fabric8.kubernetes.api.model.PodStatus.class));

        svc = reconciler.reconcile(svc, pod).getResource().orElseThrow();
        assertThat(svc.getStatus().phase).isEqualTo(Phase.Running);
        assertThat(published).extracting(e -> e.type).contains("service.created", "service.phase-changed", "service.ready");
        var ready = published.stream().filter(e -> e.type.equals("service.ready")).findFirst().orElseThrow();
        assertThat(ready.subject).isEqualTo("lobby-1");
        assertThat(ready.environment).isEqualTo("server");
        assertThat(ready.data.getAsJsonObject().getAsJsonObject("status").get("address").getAsString()).isEqualTo("10.0.0.7:25565");
        assertThat(svc.getStatus().address).isEqualTo("10.0.0.7:25565");
        assertThat(Conditions.isTrue(svc.getStatus(), Conditions.READY)).isTrue();

        svc.getSpec().desiredState = CloudServiceSpec.DesiredState.Stopped;
        svc = reconciler.reconcile(svc, pod).getResource().orElseThrow();
        assertThat(svc.getStatus().phase).isEqualTo(Phase.Stopping);
        assertThat(client.pods().inNamespace("test").withName("lobby-1").get()).isNull();

        svc = reconciler.reconcile(svc, (Pod) null).getResource().orElseThrow();
        assertThat(svc.getStatus().phase).isEqualTo(Phase.Stopped);
    }

    @Test
    void staticServiceGetsPersistentVolume() throws Exception {
        reconcileUntilPodCreated(service("environment: server\nsoftware: {name: paper}\nstatic: true\nstorage: {size: 5Gi}"));

        var pvc = client.persistentVolumeClaims().inNamespace("test").withName("lobby-1-data").get();
        assertThat(pvc).isNotNull();
        assertThat(pvc.getSpec().getResources().getRequests().get("storage").toString()).isEqualTo("5Gi");
        assertThat(pvc.getMetadata().getOwnerReferences()).isNullOrEmpty();
        Pod pod = client.pods().inNamespace("test").withName("lobby-1").get();
        assertThat(pod.getSpec().getVolumes()).anyMatch(v -> v.getName().equals("server")
                && v.getPersistentVolumeClaim() != null);
    }

    @Test
    void unknownSoftwareOrVersionFails() {
        CloudService svc = service("environment: server\nsoftware: {name: nope}");
        svc = reconciler.reconcile(svc, (Pod) null).getResource().orElseThrow();
        assertThat(svc.getStatus().phase).isEqualTo(Phase.Failed);
        assertThat(svc.getStatus().reason).isEqualTo("SoftwareNotFound");
    }

    @Test
    void failedPodWithNeverPolicyIsNotRecreated() throws Exception {
        CloudService svc = reconcileUntilPodCreated(service("environment: server\nsoftware: {name: paper}"));
        Pod pod = client.pods().inNamespace("test").withName("lobby-1").get();
        pod.setStatus(Serialization.unmarshal("""
                phase: Failed
                containerStatuses: [{name: server, state: {terminated: {exitCode: 1}}}]
                """, io.fabric8.kubernetes.api.model.PodStatus.class));

        svc = reconciler.reconcile(svc, pod).getResource().orElseThrow();
        assertThat(svc.getStatus().phase).isEqualTo(Phase.Failed);
        assertThat(svc.getStatus().reason).isEqualTo("ServerCrashed");

        client.pods().inNamespace("test").withName("lobby-1").delete();
        svc = reconciler.reconcile(svc, (Pod) null).getResource().orElseThrow();
        assertThat(client.pods().inNamespace("test").withName("lobby-1").get()).isNull();
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertThat(CloudServiceReconciler.backoff(0)).isEqualTo(Duration.ofSeconds(10));
        assertThat(CloudServiceReconciler.backoff(2)).isEqualTo(Duration.ofSeconds(40));
        assertThat(CloudServiceReconciler.backoff(10)).isEqualTo(Duration.ofMinutes(5));
    }
}
