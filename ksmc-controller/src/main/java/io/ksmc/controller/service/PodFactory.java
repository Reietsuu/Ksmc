package io.ksmc.controller.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.Environment;
import io.ksmc.api.model.Ksmc;
import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.internal.InternalApi;
import io.ksmc.controller.internal.PodAuthenticator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Construit le Pod d'un {@link CloudService} : défauts Ksmc puis {@code podTemplate} fusionné. */
public final class PodFactory {
    public static final String SERVER_DIR = "/server";
    public static final String FORWARDING_SECRET = "ksmc-forwarding";
    /** Secret des mots de passe NATS créé par le chart. */
    public static final String NATS_AUTH_SECRET = "ksmc-nats-auth";
    public static final int MINECRAFT_PORT = 25565;
    public static final int HEALTH_PORT = 8090;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Template de type image : index dans la liste des templates du service, image et dossier source. */
    public record ImageTemplate(int index, String image, String path) {
    }

    private final ControllerConfig config;
    private final KubernetesSerialization serialization = new KubernetesSerialization();

    public PodFactory(ControllerConfig config) {
        this.config = config;
    }

    public static String pvcName(CloudService svc) {
        return svc.getMetadata().getName() + "-data";
    }

    /** Mémoire du conteneur : heap + marge hors heap (metaspace, threads, buffers : 30 %, au moins 512 Mio). */
    static long containerMemoryMb(int heapMb) {
        return heapMb + Math.max(512, heapMb * 3 / 10);
    }

    public Pod build(CloudService svc, ServiceSoftware.Setup setup, List<ImageTemplate> imageTemplates, String configHash) {
        CloudServiceSpec spec = svc.getSpec();
        String name = svc.getMetadata().getName();
        String ns = svc.getMetadata().getNamespace();
        String env = spec.environment == Environment.PROXY ? "proxy" : "server";

        List<EnvVar> envVars = new ArrayList<>(List.of(
                env("KSMC_CONTROLLER_URL", config.internalUrl()),
                env("KSMC_TOKEN_FILE", "/var/run/ksmc/token/token"),
                env("KSMC_NAMESPACE", ns),
                env("KSMC_SERVICE", name),
                env("KSMC_TASK", spec.task == null ? "" : spec.task),
                env("KSMC_ENVIRONMENT", env),
                env("KSMC_SERVER_DIR", SERVER_DIR)));
        envVars.add(env("KSMC_NATS_URL", config.natsUrl()));
        if (config.natsServiceUser() != null && !config.natsServiceUser().isEmpty()) {
            envVars.add(env("KSMC_NATS_USER", config.natsServiceUser()));
            envVars.add(new EnvVarBuilder().withName("KSMC_NATS_PASSWORD").withNewValueFrom().withNewSecretKeyRef()
                    .withName(NATS_AUTH_SECRET).withKey("service-password").endSecretKeyRef().endValueFrom().build());
        }
        spec.env.forEach((k, v) -> envVars.add(env(k, v)));

        List<VolumeMount> mounts = new ArrayList<>(List.of(
                mount("server", SERVER_DIR, false),
                mount("ksmc-token", "/var/run/ksmc/token", true),
                mount("forwarding", "/var/run/ksmc/forwarding", true),
                mount("image-templates", ServiceManifestBuilder.IMAGE_TEMPLATES_DIR, false)));

        List<Container> init = new ArrayList<>();
        for (ImageTemplate t : imageTemplates) {
            String dest = ServiceManifestBuilder.IMAGE_TEMPLATES_DIR + "/" + t.index();
            init.add(new ContainerBuilder().withName("template-" + t.index()).withImage(t.image())
                    .withCommand("sh", "-c", "mkdir -p " + dest + " && cp -a " + (t.path() == null ? "/template" : t.path())
                            + "/. " + dest + "/")
                    .withVolumeMounts(mount("image-templates", ServiceManifestBuilder.IMAGE_TEMPLATES_DIR, false))
                    .build());
        }
        init.add(new ContainerBuilder().withName(PhaseCalculator.PREPARE_CONTAINER).withImage(setup.image())
                .withArgs("prepare").withEnv(envVars).withVolumeMounts(mounts)
                .withTerminationMessagePolicy("File").build());

        long memory = containerMemoryMb(spec.memoryMb);
        Container server = new ContainerBuilder().withName(PhaseCalculator.MAIN_CONTAINER).withImage(setup.image())
                .withArgs("run").withEnv(envVars).withVolumeMounts(mounts)
                .withStdin(true)
                .addNewPort().withName("minecraft").withContainerPort(MINECRAFT_PORT).withProtocol("TCP").endPort()
                .addNewPort().withName("health").withContainerPort(HEALTH_PORT).withProtocol("TCP").endPort()
                .withNewReadinessProbe().withNewHttpGet().withPath("/ready").withNewPort(HEALTH_PORT).endHttpGet()
                    .withPeriodSeconds(5).withFailureThreshold(2).endReadinessProbe()
                .withNewLivenessProbe().withNewHttpGet().withPath("/live").withNewPort(HEALTH_PORT).endHttpGet()
                    .withInitialDelaySeconds(10).withPeriodSeconds(10).withFailureThreshold(3).endLivenessProbe()
                .withNewResources()
                    .addToRequests("memory", new Quantity(memory + "Mi"))
                    .addToRequests("cpu", new Quantity("250m"))
                    .addToLimits("memory", new Quantity(memory + "Mi"))
                .endResources()
                .build();
        if (spec.resources != null) {
            server.setResources(spec.resources);
        }

        Volume serverVolume = spec.staticService
                ? new VolumeBuilder().withName("server").withNewPersistentVolumeClaim().withClaimName(pvcName(svc))
                        .endPersistentVolumeClaim().build()
                : new VolumeBuilder().withName("server").withNewEmptyDir().endEmptyDir().build();

        Pod pod = new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(ns)
                    .withLabels(Map.of(
                            Ksmc.LABEL_SERVICE, name,
                            Ksmc.LABEL_TASK, spec.task == null ? "" : spec.task,
                            Ksmc.LABEL_ENVIRONMENT, env,
                            Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY))
                    .addToAnnotations(Ksmc.ANNOTATION_CONFIG_HASH, configHash)
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .withServiceAccountName(InternalApi.SERVICE_SA)
                    .withAutomountServiceAccountToken(false)
                    .withTerminationGracePeriodSeconds((long) spec.stopTimeoutSeconds + 15)
                    .withNewSecurityContext().withRunAsUser(1000L).withRunAsGroup(1000L).withFsGroup(1000L)
                        .withRunAsNonRoot(true).endSecurityContext()
                    .withInitContainers(init)
                    .withContainers(server)
                    .withVolumes(serverVolume,
                            new VolumeBuilder().withName("image-templates").withNewEmptyDir().endEmptyDir().build(),
                            new VolumeBuilder().withName("forwarding").withNewSecret().withSecretName(FORWARDING_SECRET)
                                    .withOptional(true).endSecret().build(),
                            new VolumeBuilder().withName("ksmc-token").withNewProjected().addNewSource()
                                    .withNewServiceAccountToken().withAudience(PodAuthenticator.AUDIENCE)
                                    .withExpirationSeconds(3600L).withPath("token").endServiceAccountToken()
                                    .endSource().endProjected().build())
                .endSpec()
                .build();
        return applyPodTemplate(pod, spec.podTemplate);
    }

    /** Fusionne le fragment {@code podTemplate} (metadata + spec d'un PodTemplateSpec) dans le Pod. */
    Pod applyPodTemplate(Pod pod, ObjectNode podTemplate) {
        if (podTemplate == null || podTemplate.isEmpty()) {
            return pod;
        }
        ObjectNode base = readTree(serialization.asJson(pod));
        ObjectNode patch = podTemplate.deepCopy();
        // Le nom, le namespace et les labels d'identité Ksmc ne sont pas modifiables par un podTemplate.
        if (patch.get("metadata") instanceof ObjectNode meta) {
            meta.remove("name");
            meta.remove("namespace");
        }
        StrategicMerge.merge(base, patch);
        Pod merged = serialization.unmarshal(base.toString(), Pod.class);
        merged.getMetadata().getLabels().putAll(pod.getMetadata().getLabels());
        return merged;
    }

    private static ObjectNode readTree(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EnvVar env(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    private static VolumeMount mount(String name, String path, boolean readOnly) {
        return new VolumeMountBuilder().withName(name).withMountPath(path).withReadOnly(readOnly).build();
    }
}
