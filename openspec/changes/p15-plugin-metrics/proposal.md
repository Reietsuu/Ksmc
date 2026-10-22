# Proposal

## Why

Les métriques des services Minecraft existent côté Ksmc (Prometheus sur le port 8090, statut, dashboard), mais un
plugin ne peut pas publier les siennes (parties en cours, kills, files d'attente…) : seuls les modules du controller
ont une API de métriques. Par ailleurs, la mémoire et le GC remontés par le bridge partent dans Prometheus mais
manquent dans la `plugin-api`, le statut REST/Kubernetes et le dashboard, qui n'affichent que le TPS.

## What Changes

- **`Ksmc.api().metrics()`** dans `ksmc-plugin-api` : compteurs, jauges et timers, même forme que `ModuleMetrics`.
  Les valeurs sont publiées sur le `/metrics` du service (port 8090) sous le préfixe `ksmc_plugin_<plugin>_`,
  donc récupérées par le ServiceMonitor existant sans configuration. Limite de séries par service.
- **Mémoire et GC partout** : `CloudServiceInfo` (plugin-api), `CloudService.status` (donc REST et `kubectl`), et le
  détail d'un service dans le dashboard gagnent tas utilisé/max et temps de GC.
- Panneau Grafana « métriques de plugins » ajouté au tableau de bord du chart.

**Hors périmètre** : histogrammes à seaux (les timers exposent nombre et somme) ; métriques de plugins côté
controller (`/metrics` du controller) ; historique dans le dashboard (Grafana s'en charge).
**Laissé ouvert** : export OpenTelemetry ; métriques des bridges BungeeCord/Fabric/NeoForge au-delà de ce que
le cœur commun fournit déjà (tous les bridges utilisent le même cœur, l'API marche partout où `Ksmc.api()` marche).

## Capabilities

### New Capabilities
- (aucune)

### Modified Capabilities
- `plugin-api` : nouvelle API de métriques ; informations mémoire/GC des services.
- `observability` : les métriques de plugins apparaissent sur le `/metrics` des services.
- `web-dashboard` : le détail d'un service affiche mémoire et GC.

## Impact

- `ksmc-plugin-api` (Java 8) : interfaces `Metrics`, `Counter`, `Timer` ; champs de `CloudServiceInfo` (ajout
  compatible : nouveau constructeur, l'ancien reste).
- `ksmc-wire` : `ServiceReport.metrics` (liste d'échantillons, champ optionnel) ; `ServiceInfo` mémoire/GC.
- `ksmc-bridge-common` : registre des métriques, envoi dans le rapport toutes les 5 s.
- `ksmc-wrapper` : `ServiceMetrics` rend les échantillons ; `ksmc-api`/controller : champs de statut + CRD.
- `dashboard` : bloc mémoire/GC ; chart : panneau Grafana. Aucune dépendance nouvelle.
