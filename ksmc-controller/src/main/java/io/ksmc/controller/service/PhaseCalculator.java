package io.ksmc.controller.service;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import java.util.List;
import java.util.Set;

/** Déduit la phase d'un service de l'état de son Pod (fonction pure, testée unitairement). */
public final class PhaseCalculator {
    public static final String MAIN_CONTAINER = "server";
    public static final String PREPARE_CONTAINER = "prepare";
    private static final Set<String> IMAGE_ERRORS = Set.of("ErrImagePull", "ImagePullBackOff", "InvalidImageName");
    private static final Set<String> CONFIG_ERRORS = Set.of("CreateContainerConfigError", "CreateContainerError");

    /** Résultat : phase, raison courte, message, et si le serveur répond (prêt). */
    public record Result(Phase phase, String reason, String message, boolean ready, boolean terminated, Integer exitCode) {
        static Result of(Phase phase, String reason, String message) {
            return new Result(phase, reason, message, false, false, null);
        }
    }

    private PhaseCalculator() {
    }

    public static Result compute(Pod pod) {
        if (pod == null) {
            return Result.of(Phase.Pending, "PodNotCreated", null);
        }
        if (pod.getMetadata().getDeletionTimestamp() != null) {
            return Result.of(Phase.Stopping, "Stopping", null);
        }
        var status = pod.getStatus();
        if (status == null) {
            return Result.of(Phase.Pending, "Scheduling", null);
        }
        PodCondition scheduled = condition(status.getConditions(), "PodScheduled");
        if (scheduled != null && "False".equals(scheduled.getStatus())) {
            return Result.of(Phase.Pending, scheduled.getReason() == null ? "Unschedulable" : scheduled.getReason(),
                    scheduled.getMessage());
        }
        for (ContainerStatus init : nullSafe(status.getInitContainerStatuses())) {
            Result r = fromContainer(init, true);
            if (r != null) {
                return r;
            }
        }
        ContainerStatus main = nullSafe(status.getContainerStatuses()).stream()
                .filter(c -> MAIN_CONTAINER.equals(c.getName())).findFirst().orElse(null);
        if (main == null) {
            return Result.of("Pending".equals(status.getPhase()) ? Phase.Preparing : Phase.Pending, "ContainerCreating", null);
        }
        Result r = fromContainer(main, false);
        if (r != null) {
            return r;
        }
        if (main.getState() != null && main.getState().getRunning() != null) {
            return Boolean.TRUE.equals(main.getReady())
                    ? new Result(Phase.Running, "Ready", null, true, false, null)
                    : Result.of(Phase.Starting, "Starting", null);
        }
        return Result.of(Phase.Pending, "ContainerCreating", null);
    }

    /** Analyse un conteneur ; {@code null} = rien de déterminant (conteneur d'init terminé avec succès). */
    private static Result fromContainer(ContainerStatus c, boolean init) {
        var state = c.getState();
        if (state == null) {
            return init ? Result.of(Phase.Preparing, "Preparing", null) : null;
        }
        ContainerStateWaiting waiting = state.getWaiting();
        if (waiting != null) {
            String reason = waiting.getReason();
            if (reason != null && IMAGE_ERRORS.contains(reason)) {
                return Result.of(Phase.Pending, "ImagePullFailed", c.getImage() + ": " + waiting.getMessage());
            }
            if (reason != null && CONFIG_ERRORS.contains(reason)) {
                return Result.of(Phase.Failed, reason, waiting.getMessage());
            }
            return init ? Result.of(Phase.Preparing, "Preparing", null) : Result.of(Phase.Starting, "Starting", null);
        }
        if (state.getRunning() != null) {
            return init ? Result.of(Phase.Preparing, "Preparing", null) : null;
        }
        ContainerStateTerminated t = state.getTerminated();
        if (t != null) {
            int code = t.getExitCode() == null ? -1 : t.getExitCode();
            if (init && code == 0) {
                return null;
            }
            String message = t.getMessage();
            String reason;
            if (init) {
                // Message de terminaison écrit par le wrapper : "<Raison>: <détail>".
                reason = "PrepareFailed";
                if (message != null && message.matches("^[A-Z][A-Za-z]+: [\\s\\S]*")) {
                    reason = message.substring(0, message.indexOf(':'));
                    message = message.substring(message.indexOf(':') + 2);
                }
            } else if (code == 0) {
                return new Result(Phase.Stopped, "Exited", null, false, true, 0);
            } else {
                reason = "OOMKilled".equals(t.getReason()) ? "OOMKilled" : "ServerCrashed";
                message = "exit code " + code + (message == null ? "" : ": " + message);
            }
            return new Result(Phase.Failed, reason, message, false, true, code);
        }
        return null;
    }

    private static PodCondition condition(List<PodCondition> conditions, String type) {
        return nullSafe(conditions).stream().filter(c -> type.equals(c.getType())).findFirst().orElse(null);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
