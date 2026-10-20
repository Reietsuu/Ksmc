package io.ksmc.api.module;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.wire.messaging.EventApi;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingTransport;
import java.nio.file.Path;
import org.slf4j.Logger;

/** Accès d'un module au cloud. Valable entre {@code onEnable} et {@code onDisable}. */
public interface ModuleContext {

    ModuleDescriptor descriptor();

    /** Enregistrement des implémentations de SPI (résolveurs, templates, Pods, scaling...). */
    Registry registry();

    /** Messagerie du controller (channels, événements, KV). Lève une exception si NATS est indisponible. */
    MessagingTransport messaging();

    /** Raccourci de {@code messaging().events()} ; les abonnements sont fermés à la désactivation. */
    EventApi events();

    /** Bucket clé-valeur du namespace ; les surveillances sont fermées à la désactivation. */
    KeyValueApi kv(String bucket);

    /** Client Kubernetes du controller (mêmes droits que lui, voir la documentation des modules). */
    KubernetesClient kubernetes();

    /** Namespace géré par le controller. */
    String namespace();

    /** Routes REST servies sous {@code /api/v1/modules/<id>/}. */
    RestRoutes rest();

    /** Commandes déclaratives découvertes par la CLI ({@code GET /api/v1/modules/<id>/cli}). */
    void cliCommand(CliCommand command);

    /** Page du dashboard : bundle ES module servi sous {@code /modules/<id>/ui.js}. */
    void dashboardPage(DashboardPage page);

    /** Configuration actuelle ({@code spec.config}), objet vide si absente. */
    JsonNode config();

    /** Métriques Prometheus du module ({@code ksmc_module_<id>_...} sur {@code /metrics} du controller). */
    ModuleMetrics metrics();

    /** Dossier persistant propre au module. */
    Path dataDirectory();

    Logger logger();
}
