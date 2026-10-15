package io.ksmc.controller.software;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.internal.InternalApi;
import io.ksmc.controller.internal.PodAuthenticator;
import io.ksmc.controller.template.Digests;
import java.io.IOException;
import java.util.Map;

/**
 * Lance (une seule fois par artefact) les Jobs de construction des logiciels non téléchargeables directement.
 * Le nom du Job dérive de la clé de cache : deux services qui demandent le même artefact partagent le même Job.
 */
public final class BuildJobRunner {
    public static final String LABEL_BUILD = Ksmc.GROUP + "/build";
    static final String WRAPPER_JAR = "/opt/ksmc/ksmc-wrapper.jar";

    public enum State { READY, BUILDING, FAILED }

    public record Status(State state, String message) {
    }

    private final KubernetesClient client;
    private final ArtifactStorage storage;
    private final ControllerConfig config;

    public BuildJobRunner(KubernetesClient client, ArtifactStorage storage, ControllerConfig config) {
        this.client = client;
        this.storage = storage;
        this.config = config;
    }

    public static String jobName(ArtifactPlan.BuildJob plan) {
        return "ksmc-build-" + Digests.sha256(plan.cacheKey()).substring(0, 12);
    }

    /** État de l'artefact ; démarre le Job s'il n'existe ni artefact ni Job. */
    public Status ensure(String namespace, ArtifactPlan.BuildJob plan) throws IOException {
        if (storage.exists("artifacts/" + plan.cacheKey())) {
            return new Status(State.READY, null);
        }
        String name = jobName(plan);
        Job job = client.batch().v1().jobs().inNamespace(namespace).withName(name).get();
        if (job == null) {
            try {
                client.batch().v1().jobs().inNamespace(namespace).resource(build(name, plan)).create();
            } catch (KubernetesClientException e) {
                if (e.getCode() != 409) {
                    throw e;
                }
            }
            return new Status(State.BUILDING, "build job " + name + " started");
        }
        var status = job.getStatus();
        if (status != null && status.getFailed() != null && status.getFailed() > 0 && (status.getActive() == null
                || status.getActive() == 0)) {
            return new Status(State.FAILED, "build job " + name + " failed (kubectl logs job/" + name + ")");
        }
        if (status != null && status.getSucceeded() != null && status.getSucceeded() > 0) {
            // Job terminé mais artefact absent (stockage perdu) : on relance.
            client.batch().v1().jobs().inNamespace(namespace).withName(name).delete();
            return new Status(State.BUILDING, "artifact missing after build, restarting " + name);
        }
        return new Status(State.BUILDING, "build job " + name + " running");
    }

    Job build(String name, ArtifactPlan.BuildJob plan) {
        String spec = Json.MAPPER.valueToTree(Map.of(
                "downloads", plan.downloads(),
                "javaArgs", plan.javaArgs(),
                "outputDir", plan.outputDir(),
                "cacheKey", plan.cacheKey())).toString();
        return new JobBuilder()
                .withNewMetadata().withName(name)
                    .addToLabels(Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY).addToLabels(LABEL_BUILD, "true")
                    .addToAnnotations(Ksmc.GROUP + "/artifact", plan.cacheKey())
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(1)
                    .withTtlSecondsAfterFinished(3600)
                    .withActiveDeadlineSeconds(3600L)
                    .withNewTemplate()
                        .withNewMetadata().addToLabels(LABEL_BUILD, "true").endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withServiceAccountName(InternalApi.BUILDER_SA)
                            .addNewVolume().withName("work").withNewEmptyDir().endEmptyDir().endVolume()
                            .addNewVolume().withName("ksmc-token").withNewProjected().addNewSource()
                                .withNewServiceAccountToken().withAudience(PodAuthenticator.AUDIENCE)
                                .withExpirationSeconds(3600L).withPath("token").endServiceAccountToken()
                            .endSource().endProjected().endVolume()
                            .addNewContainer()
                                .withName("build")
                                .withImage(config.runtimeImageFor(plan.javaVersion()))
                                .withCommand("java", "-jar", WRAPPER_JAR, "build")
                                .withWorkingDir("/work")
                                .addNewEnv().withName("KSMC_BUILD").withValue(spec).endEnv()
                                .addNewEnv().withName("KSMC_CONTROLLER_URL").withValue(config.internalUrl()).endEnv()
                                .addNewEnv().withName("KSMC_TOKEN_FILE").withValue("/var/run/ksmc/token").endEnv()
                                .addNewVolumeMount().withName("work").withMountPath("/work").endVolumeMount()
                                .addNewVolumeMount().withName("ksmc-token").withMountPath("/var/run/ksmc")
                                    .withReadOnly(true).endVolumeMount()
                                .withNewResources()
                                    .addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity("1Gi"))
                                    .addToLimits("memory", new io.fabric8.kubernetes.api.model.Quantity("3Gi"))
                                .endResources()
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }
}
