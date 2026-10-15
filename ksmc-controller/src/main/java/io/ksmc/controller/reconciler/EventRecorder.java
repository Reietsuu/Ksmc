package io.ksmc.controller.reconciler;

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Émet des événements Kubernetes ({@code kubectl describe}) sur les ressources Ksmc. */
public final class EventRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(EventRecorder.class);
    private final KubernetesClient client;

    public EventRecorder(KubernetesClient client) {
        this.client = client;
    }

    public void normal(HasMetadata target, String reason, String message) {
        emit(target, "Normal", reason, message);
    }

    public void warning(HasMetadata target, String reason, String message) {
        emit(target, "Warning", reason, message);
    }

    private void emit(HasMetadata target, String type, String reason, String message) {
        try {
            String now = Instant.now().toString();
            client.v1().events().inNamespace(target.getMetadata().getNamespace()).resource(new EventBuilder()
                    .withNewMetadata().withName(target.getMetadata().getName() + "." + UUID.randomUUID().toString().substring(0, 8))
                    .endMetadata()
                    .withNewInvolvedObject().withApiVersion(target.getApiVersion()).withKind(target.getKind())
                        .withName(target.getMetadata().getName()).withNamespace(target.getMetadata().getNamespace())
                        .withUid(target.getMetadata().getUid()).endInvolvedObject()
                    .withType(type).withReason(reason).withMessage(message == null ? "" : message)
                    .withFirstTimestamp(now).withLastTimestamp(now).withCount(1)
                    .withNewSource().withComponent("ksmc-controller").endSource()
                    .build()).create();
        } catch (RuntimeException e) {
            LOG.debug("événement non émis : {}", e.getMessage());
        }
    }
}
