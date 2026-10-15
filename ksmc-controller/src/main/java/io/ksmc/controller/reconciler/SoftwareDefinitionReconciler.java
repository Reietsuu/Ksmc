package io.ksmc.controller.reconciler;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.model.SoftwareDefinitionStatus;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.controller.software.SoftwareCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publie dans le statut des {@link SoftwareDefinition} les versions connues et la dernière stable. */
public final class SoftwareDefinitionReconciler implements Reconciler<SoftwareDefinition> {
    private static final Logger LOG = LoggerFactory.getLogger(SoftwareDefinitionReconciler.class);
    static final int MAX_VERSIONS = 50;
    static final Duration REFRESH = Duration.ofHours(6);
    static final Duration RETRY = Duration.ofMinutes(5);

    private final SoftwareCatalog catalog;

    public SoftwareDefinitionReconciler(SoftwareCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public UpdateControl<SoftwareDefinition> reconcile(SoftwareDefinition def, Context<SoftwareDefinition> context) {
        SoftwareDefinitionStatus status = def.getStatus() == null ? new SoftwareDefinitionStatus() : def.getStatus();
        long generation = def.getMetadata().getGeneration() == null ? 0 : def.getMetadata().getGeneration();
        Duration next = REFRESH;
        try {
            List<String> versions = catalog.versions(def);
            status.versions = versions.subList(0, Math.min(MAX_VERSIONS, versions.size()));
            ResolvedSoftware latest = catalog.resolve(def, "latest");
            status.latest = latest.build() == null || latest.build().equals(latest.version())
                    ? latest.version() : latest.version() + "@" + latest.build();
            Conditions.set(status, generation, Conditions.READY, true, "Resolved", null);
        } catch (Exception e) {
            LOG.warn("Résolution impossible pour {}: {}", def.getMetadata().getName(), e.getMessage());
            Conditions.set(status, generation, Conditions.READY, false, "ResolverError", e.getMessage());
            next = RETRY;
        }
        status.observedGeneration = generation;
        status.lastRefresh = Instant.now().toString();
        def.setStatus(status);
        return UpdateControl.patchStatus(def).rescheduleAfter(next);
    }
}
