package io.ksmc.api.spi;

import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.ServiceTask;
import java.time.Instant;
import java.util.List;

/**
 * Décide combien de services une task doit avoir. Les stratégies listées dans
 * {@code spec.autoscaling.strategies} (défaut : {@code static}) sont combinées : la cible est le maximum de leurs
 * propositions, bornée par {@code minServiceCount} et {@code maxServiceCount}. Un module peut en ajouter.
 */
public interface ScalingStrategy {

    /** Nom utilisé dans {@code spec.autoscaling.strategies}. */
    String name();

    /** Nombre de services proposé, ou {@link #NO_OPINION}. */
    int desiredServices(Context context);

    int NO_OPINION = -1;

    /**
     * @param task     la task (spec et statut)
     * @param services services actuels de la task, hors services en cours d'arrêt
     * @param now      horloge de référence
     */
    record Context(ServiceTask task, List<CloudService> services, Instant now) {
    }
}
