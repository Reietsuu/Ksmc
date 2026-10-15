package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;

/** Champs de statut communs à toutes les ressources Ksmc. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KsmcStatus {
    /** Génération de la spec prise en compte par le controller. */
    public Long observedGeneration;
    /** Conditions au format Kubernetes standard ({@code Ready}, {@code Degraded}...). */
    public List<Condition> conditions = new ArrayList<>();
}
