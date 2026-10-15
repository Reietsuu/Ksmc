package io.ksmc.controller.reconciler;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.ksmc.api.model.KsmcStatus;
import io.ksmc.api.model.ServiceGroup;

/**
 * Statut des {@link ServiceGroup}. La propagation des changements vers les tasks est assurée par
 * {@link ServiceTaskReconciler}, qui surveille les groupes de son namespace.
 */
public final class ServiceGroupReconciler implements Reconciler<ServiceGroup> {

    @Override
    public UpdateControl<ServiceGroup> reconcile(ServiceGroup group, Context<ServiceGroup> context) {
        KsmcStatus status = group.getStatus() == null ? new KsmcStatus() : group.getStatus();
        long generation = group.getMetadata().getGeneration() == null ? 0 : group.getMetadata().getGeneration();
        if (status.observedGeneration != null && status.observedGeneration == generation) {
            return UpdateControl.noUpdate();
        }
        status.observedGeneration = generation;
        Conditions.set(status, generation, Conditions.READY, true, "Accepted", null);
        group.setStatus(status);
        return UpdateControl.patchStatus(group);
    }
}
