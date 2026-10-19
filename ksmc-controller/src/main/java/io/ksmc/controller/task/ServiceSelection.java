package io.ksmc.controller.task;

import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Nommage des services d'une task et choix des services à arrêter. */
public final class ServiceSelection {
    private ServiceSelection() {
    }

    /** Numéro d'un service {@code <task>-<n>}, ou -1. */
    public static int number(String task, String serviceName) {
        String prefix = task + "-";
        if (!serviceName.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(serviceName.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Les {@code count} plus petits numéros libres. */
    public static List<String> nextNames(String task, Collection<String> existing, int count) {
        Set<Integer> used = new HashSet<>();
        existing.forEach(n -> used.add(number(task, n)));
        List<String> out = new java.util.ArrayList<>();
        for (int i = 1; out.size() < count; i++) {
            if (!used.contains(i)) {
                out.add(task + "-" + i);
            }
        }
        return out;
    }

    /**
     * Services à arrêter en priorité : d'abord ceux qui ne sont pas en {@code Running} (pas encore utiles), puis le moins
     * de joueurs, puis le numéro le plus élevé.
     */
    public static List<CloudService> toStop(String task, List<CloudService> services, int count) {
        return toStop(task, services, count, java.util.Map.of());
    }

    /** Comme {@link #toStop(String, List, int)}, en préférant parmi les vides celui qui l'est depuis le plus longtemps. */
    public static List<CloudService> toStop(String task, List<CloudService> services, int count,
            java.util.Map<String, String> emptySince) {
        return services.stream()
                .sorted(Comparator
                        .comparing((CloudService s) -> isRunning(s) ? 1 : 0)
                        .thenComparing(s -> players(s))
                        .thenComparing(s -> emptySince.getOrDefault(s.getMetadata().getName(), "~"))
                        .thenComparing(s -> -number(task, s.getMetadata().getName())))
                .limit(Math.max(0, count))
                .toList();
    }

    public static boolean isRunning(CloudService s) {
        return s.getStatus() != null && s.getStatus().phase == CloudServiceStatus.Phase.Running;
    }

    public static int players(CloudService s) {
        return s.getStatus() == null || s.getStatus().onlinePlayers == null ? 0 : s.getStatus().onlinePlayers;
    }
}
