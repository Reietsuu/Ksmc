package io.ksmc.controller.software;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.storage.FileArtifactStorage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@EnableKubernetesMockClient(crud = true)
class BuildJobRunnerTest {
    KubernetesClient client;

    @TempDir
    Path tmp;

    final ArtifactPlan.BuildJob plan = new ArtifactPlan.BuildJob(17, Map.of("https://x/BuildTools.jar", "BuildTools.jar"),
            List.of("-jar", "BuildTools.jar", "--rev", "1.20.4"), "out", "buildtools/spigot/1.20.4/server.zip");

    BuildJobRunner runner() throws Exception {
        ControllerConfig cfg = ControllerConfig.fromEnv(Map.of("KSMC_RUNTIME_TAG", "0.1.0",
                "KSMC_INTERNAL_URL", "http://ksmc-controller.ksmc.svc:8080"));
        return new BuildJobRunner(client, new FileArtifactStorage(tmp), cfg);
    }

    @Test
    void startsOneJobPerArtifact() throws Exception {
        BuildJobRunner runner = runner();

        assertThat(runner.ensure("test", plan).state()).isEqualTo(BuildJobRunner.State.BUILDING);
        assertThat(runner.ensure("test", plan).state()).isEqualTo(BuildJobRunner.State.BUILDING);

        List<Job> jobs = client.batch().v1().jobs().inNamespace("test").list().getItems();
        assertThat(jobs).hasSize(1);
        var container = jobs.getFirst().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertThat(container.getImage()).isEqualTo("ghcr.io/reietsuu/ksmc-runtime:0.1.0-java17");
        assertThat(container.getEnv()).anySatisfy(e -> {
            assertThat(e.getName()).isEqualTo("KSMC_BUILD");
            assertThat(e.getValue()).contains("buildtools/spigot/1.20.4/server.zip").contains("--rev");
        });
        assertThat(jobs.getFirst().getSpec().getTemplate().getSpec().getServiceAccountName()).isEqualTo("ksmc-builder");
    }

    @Test
    void readyWhenArtifactExists() throws Exception {
        FileArtifactStorage storage = new FileArtifactStorage(tmp);
        storage.write("artifacts/" + plan.cacheKey(), new ByteArrayInputStream(new byte[] {1}));

        assertThat(runner().ensure("test", plan).state()).isEqualTo(BuildJobRunner.State.READY);
        assertThat(client.batch().v1().jobs().inNamespace("test").list().getItems()).isEmpty();
    }

    @Test
    void reportsFailedJob() throws Exception {
        BuildJobRunner runner = runner();
        runner.ensure("test", plan);
        client.batch().v1().jobs().inNamespace("test").withName(BuildJobRunner.jobName(plan))
                .editStatus(j -> {
                    j.setStatus(new JobStatusBuilder().withFailed(2).withActive(0).build());
                    return j;
                });

        BuildJobRunner.Status status = runner.ensure("test", plan);
        assertThat(status.state()).isEqualTo(BuildJobRunner.State.FAILED);
        assertThat(status.message()).contains("kubectl logs");
    }
}
