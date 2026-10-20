# Proposal

## Why

En production il faut savoir si le cloud va bien (joueurs, TPS, services en échec, latence de
démarrage) avec les outils standards de Kubernetes, et être alerté avant que les joueurs ne le remarquent.

## What Changes

- Métriques Prometheus du controller (services, joueurs, réconciliations, modules, API) et des services (joueurs, TPS, mémoire JVM).
- `ServiceMonitor`/`PodMonitor` optionnels dans le chart, tableau de bord Grafana et règles d'alerte d'exemple.
- Logs structurés JSON optionnels, niveau configurable à chaud.
- Les modules peuvent publier leurs propres métriques.

**Hors périmètre** : traçage distribué (laissé ouvert : OpenTelemetry activable plus tard).

## Capabilities

### New Capabilities
- `observability` : métriques, logs et alertes du cloud.

### Modified Capabilities
- (aucune)

## Impact

Micrometer + registre Prometheus dans le controller, endpoint métriques du wrapper, templates de chart.
