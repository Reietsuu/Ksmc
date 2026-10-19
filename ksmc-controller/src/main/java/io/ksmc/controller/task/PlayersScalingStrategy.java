package io.ksmc.controller.task;

import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.api.spi.ScalingStrategy;
import java.time.Instant;
import java.util.List;

/**
 * Stratégie {@code players} : un service de plus quand le remplissage moyen des services routables {@code Running}
 * dépasse {@code scaleUpThreshold} ; un de moins par service vide depuis {@code idleStopSeconds}.
 */
public final class PlayersScalingStrategy implements ScalingStrategy {
    public static final String NAME = "players";
    public static final double DEFAULT_THRESHOLD = 0.8;
    public static final int DEFAULT_IDLE_SECONDS = 300;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int desiredServices(Context context) {
        List<CloudService> routable = Autoscaler.routable(context.services());
        Double fill = fill(routable);
        if (fill != null && fill > threshold(context.task())) {
            return routable.size() + 1;
        }
        return routable.size() - idle(context.task(), routable, context.now()).size();
    }

    /** Joueurs / places des services routables {@code Running} qui annoncent leur capacité ; {@code null} sans donnée. */
    public static Double fill(List<CloudService> services) {
        long online = 0;
        long capacity = 0;
        for (CloudService s : services) {
            if (ServiceSelection.isRunning(s) && s.getStatus().maxPlayers != null && s.getStatus().maxPlayers > 0) {
                online += ServiceSelection.players(s);
                capacity += s.getStatus().maxPlayers;
            }
        }
        return capacity == 0 ? null : (double) online / capacity;
    }

    /** Services routables {@code Running} vides depuis au moins {@code idleStopSeconds}. */
    public static List<CloudService> idle(ServiceTask task, List<CloudService> routable, Instant now) {
        Instant limit = now.minusSeconds(idleSeconds(task));
        return routable.stream()
                .filter(ServiceSelection::isRunning)
                .filter(s -> ServiceSelection.players(s) == 0)
                .filter(s -> {
                    Instant since = Autoscaler.emptySince(task, s.getMetadata().getName());
                    return since != null && !since.isAfter(limit);
                })
                .toList();
    }

    public static double threshold(ServiceTask task) {
        ServiceTaskSpec.Autoscaling a = task.getSpec().autoscaling;
        return a == null || a.scaleUpThreshold == null ? DEFAULT_THRESHOLD : a.scaleUpThreshold;
    }

    public static int idleSeconds(ServiceTask task) {
        ServiceTaskSpec.Autoscaling a = task.getSpec().autoscaling;
        return a == null || a.idleStopSeconds == null ? DEFAULT_IDLE_SECONDS : a.idleStopSeconds;
    }
}
