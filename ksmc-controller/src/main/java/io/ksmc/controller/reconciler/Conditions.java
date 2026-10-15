package io.ksmc.controller.reconciler;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.ksmc.api.model.KsmcStatus;
import java.time.Instant;
import java.util.Objects;

/** Gestion des conditions Kubernetes standard dans les statuts Ksmc. */
public final class Conditions {
    public static final String READY = "Ready";

    private Conditions() {
    }

    /** Pose ou met à jour une condition ; {@code lastTransitionTime} ne change que si le statut change. */
    public static void set(KsmcStatus status, long generation, String type, boolean value, String reason, String message) {
        String v = value ? "True" : "False";
        Condition existing = status.conditions.stream().filter(c -> type.equals(c.getType())).findFirst().orElse(null);
        String transition = existing != null && v.equals(existing.getStatus())
                ? existing.getLastTransitionTime() : Instant.now().toString();
        if (existing != null) {
            status.conditions.remove(existing);
        }
        status.conditions.add(new ConditionBuilder().withType(type).withStatus(v).withReason(reason)
                .withMessage(message == null ? "" : message).withObservedGeneration(generation)
                .withLastTransitionTime(transition).build());
    }

    public static boolean isTrue(KsmcStatus status, String type) {
        return status != null && status.conditions.stream()
                .anyMatch(c -> type.equals(c.getType()) && Objects.equals("True", c.getStatus()));
    }
}
