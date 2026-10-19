package io.ksmc.controller.service;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.Ksmc;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extrait des dernières lignes d'un service en échec, conservé dans le ConfigMap {@code <service>-lastlog}. Possédé par
 * la task du service (s'il en a une) pour survivre au remplacement du service, sinon par le service.
 */
public final class LastLog {

    public static final int LINES = 500;
    private static final Logger LOG = LoggerFactory.getLogger(LastLog.class);

    private LastLog() {
    }

    public static String name(String service) {
        return service + "-lastlog";
    }

    /** Capture best effort : une erreur est journalisée sans gêner la réconciliation. */
    public static void capture(KubernetesClient client, CloudService svc, String pod, String reason, Instant now) {
        String ns = svc.getMetadata().getNamespace();
        try {
            String log = client.pods().inNamespace(ns).withName(pod).inContainer("server").tailingLines(LINES).getLog();
            List<OwnerReference> owners = svc.getMetadata().getOwnerReferences() == null ? List.of()
                    : svc.getMetadata().getOwnerReferences().stream().filter(o -> "ServiceTask".equals(o.getKind()))
                            .map(o -> new OwnerReferenceBuilder(o).withController(false).withBlockOwnerDeletion(false)
                                    .build())
                            .toList();
            if (owners.isEmpty()) {
                owners = List.of(new OwnerReferenceBuilder().withApiVersion(svc.getApiVersion()).withKind(svc.getKind())
                        .withName(svc.getMetadata().getName()).withUid(svc.getMetadata().getUid()).build());
            }
            String task = svc.getMetadata().getLabels() == null ? null : svc.getMetadata().getLabels().get(Ksmc.LABEL_TASK);
            ConfigMapBuilder cm = new ConfigMapBuilder().withNewMetadata().withName(name(svc.getMetadata().getName()))
                    .withNamespace(ns).addToLabels(Ksmc.LABEL_SERVICE, svc.getMetadata().getName())
                    .addToLabels(Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY).withOwnerReferences(owners).endMetadata()
                    .addToData("log", log == null ? "" : log)
                    .addToData("reason", reason == null ? "" : reason)
                    .addToData("capturedAt", now.toString());
            if (task != null) {
                cm.editMetadata().addToLabels(Ksmc.LABEL_TASK, task).endMetadata();
            }
            client.configMaps().inNamespace(ns).resource(cm.build()).createOr(c -> c.update());
        } catch (RuntimeException e) {
            LOG.debug("Extrait de logs de {} non conservé : {}", svc.getMetadata().getName(), e.getMessage());
        }
    }
}
