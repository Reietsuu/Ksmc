package io.ksmc.controller.task;

import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.api.model.ServiceTaskStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Règles communes de l'autoscaling, appliquées à la cible combinée des stratégies : anti-oscillation
 * ({@code cooldownSeconds}), démarrages simultanés ({@code maxConcurrentStarts}) et mémoire des services vides.
 */
public final class Autoscaler {
    public static final int DEFAULT_COOLDOWN_SECONDS = 60;
    public static final String SCALED_UP = "ScaledUp";
    public static final String SCALED_DOWN = "ScaledDown";

    private Autoscaler() {
    }

    /** Vrai si la task utilise une autre stratégie que {@code static} ou des services préparés. */
    public static boolean enabled(ServiceTask task) {
        ServiceTaskSpec.Autoscaling a = task.getSpec().autoscaling;
        return a != null && (a.strategies.stream().anyMatch(s -> !StaticScalingStrategy.NAME.equals(s))
                || preparedWanted(task) > 0);
    }

    public static int preparedWanted(ServiceTask task) {
        ServiceTaskSpec.Autoscaling a = task.getSpec().autoscaling;
        return a == null || a.preparedServices == null ? 0 : a.preparedServices;
    }

    public static boolean prepared(CloudService s) {
        Map<String, String> labels = s.getMetadata().getLabels();
        return labels != null && "true".equals(labels.get(Ksmc.LABEL_PREPARED));
    }

    public static List<CloudService> routable(List<CloudService> services) {
        return services.stream().filter(s -> !prepared(s)).toList();
    }

    public static ServiceTaskStatus.AutoscalingState state(ServiceTask task) {
        ServiceTaskStatus st = task.getStatus();
        return st == null ? null : st.autoscaling;
    }

    public static Instant emptySince(ServiceTask task, String service) {
        ServiceTaskStatus.AutoscalingState s = state(task);
        String v = s == null || s.emptySince == null ? null : s.emptySince.get(service);
        return v == null ? null : Instant.parse(v);
    }

    /**
     * Met à jour la mémoire des services vides : un service {@code Running} sans joueur est vide depuis la première
     * observation ; dès qu'il a un joueur (ou n'est plus actif) il est retiré.
     */
    public static void observe(ServiceTaskStatus.AutoscalingState state, List<CloudService> active, Instant now) {
        Map<String, String> next = new LinkedHashMap<>();
        for (CloudService s : active) {
            String name = s.getMetadata().getName();
            if (ServiceSelection.isRunning(s) && !prepared(s) && ServiceSelection.players(s) == 0) {
                String since = state.emptySince == null ? null : state.emptySince.get(name);
                next.put(name, since != null ? since : now.toString());
            }
        }
        state.emptySince = next;
    }

    /** Décision : cible de services routables et, si elle change, le sens et la raison. */
    public record Decision(int target, String direction, String reason) {
    }

    /**
     * @param current   services routables actifs
     * @param starting  services routables pas encore {@code Running}
     * @param proposed  cible combinée des stratégies, bornée par min/max
     * @param baseline  minimum à atteindre sans délai (min, démarrages manuels, minimum programmé)
     */
    public static Decision decide(ServiceTask task, int current, int starting, int proposed, int baseline, Instant now,
            String reason) {
        ServiceTaskSpec.Autoscaling a = task.getSpec().autoscaling;
        ServiceTaskStatus.AutoscalingState s = state(task);
        int cooldown = a == null || a.cooldownSeconds == null ? DEFAULT_COOLDOWN_SECONDS : a.cooldownSeconds;
        boolean inCooldown = s != null && s.lastScaleTime != null
                && now.isBefore(Instant.parse(s.lastScaleTime).plusSeconds(cooldown));
        String last = s == null ? null : s.lastDecision;
        int target = proposed;
        if (proposed > current) {
            if (inCooldown && SCALED_DOWN.equals(last) && current >= baseline) {
                target = current;
            } else {
                int maxStarts = a == null || a.maxConcurrentStarts == null ? 1 : a.maxConcurrentStarts;
                int allowed = Math.max(0, maxStarts - starting);
                target = Math.max(Math.min(proposed, current + allowed), Math.min(proposed, baseline));
            }
        } else if (proposed < current && inCooldown && SCALED_UP.equals(last)) {
            target = current;
        }
        String direction = target > current ? SCALED_UP : target < current ? SCALED_DOWN : null;
        // Atteindre le minimum de la task (création, services perdus) n'est pas une décision d'autoscaling.
        int specMinimum = task.getSpec().minServiceCount + task.getSpec().extraServices;
        if (SCALED_UP.equals(direction) && current < specMinimum && target <= specMinimum) {
            direction = null;
        }
        return new Decision(target, direction, reason);
    }
}
