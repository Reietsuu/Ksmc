package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.model.annotation.LabelSelector;
import io.fabric8.kubernetes.model.annotation.StatusReplicas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceTaskStatus extends KsmcStatus {
    @StatusReplicas
    public Integer readyServices;
    public Integer totalServices;
    /** Affichage {@code prêts/total} pour kubectl. */
    public String ready;
    /** Nombre de services par phase. */
    public Map<String, Integer> phases = new LinkedHashMap<>();
    public Integer onlinePlayers;
    /** Sélecteur de labels des services (sous-ressource scale). */
    @LabelSelector
    public String selector;
    public Effective effective;
    public FailureWindow failureWindow;
    public AutoscalingState autoscaling;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Effective {
        public List<String> groups = new ArrayList<>();
        public List<String> templates = new ArrayList<>();
        public String configHash;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FailureWindow {
        public String windowStart;
        public Integer failures;
        public String nextAttemptAfter;
        public String lastReason;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AutoscalingState {
        /** Services routables visés (hors services préparés). */
        public Integer desiredServices;
        public Integer preparedServices;
        /** Remplissage moyen observé des services Running (joueurs / places). */
        public Double observedFill;
        public String lastScaleTime;
        /** {@code ScaledUp} ou {@code ScaledDown}. */
        public String lastDecision;
        public String lastReason;
        /** Service → instant depuis lequel il est vide (survit aux redémarrages du controller). */
        public Map<String, String> emptySince = new LinkedHashMap<>();
    }
}
