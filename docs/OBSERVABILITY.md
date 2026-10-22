# Observabilité

Ksmc expose des métriques Prometheus (controller et services), des alertes et un tableau de bord Grafana prêts
à l'emploi, et des logs texte ou JSON dont le niveau se change à chaud.

## Métriques du controller

`GET /metrics` sur le port HTTP du controller (8080, sans authentification, comme `/healthz`) :

| Métrique | Type | Labels | Description |
|---|---|---|---|
| `ksmc_services` | jauge | `task`, `phase` | services par task et phase (toutes les phases, 0 compris) |
| `ksmc_players` | jauge | `task` | joueurs en ligne par task |
| `ksmc_task_degraded` | jauge | `task` | 1 si la task est dégradée (crash en boucle) |
| `ksmc_service_restarts` | jauge | `task`, `service` | redémarrages du conteneur d'un service |
| `ksmc_service_frozen_total` | compteur | `task` | proxies déclarés figés par leur chien de garde |
| `ksmc_modules` | jauge | `module`, `phase` | 1 pour la phase actuelle de chaque module |
| `ksmc_service_start_seconds` | histogramme | `task` | durée conteneur lancé → service prêt |
| `ksmc_reconciliations_seconds` | histogramme | `controller`, `operation`, `result` | réconciliations (`result` = `success` ou `error`) |
| `ksmc_api_requests_seconds` | histogramme | `method`, `route`, `status` | requêtes de l'API REST (route déclarée, ex. `/api/v1/tasks/{name}`) |
| `jvm_*`, `process_*`, `system_*` | | | JVM du controller |

L'état des ressources est relu à la collecte, au plus toutes les 5 s.

```bash
kubectl -n ksmc port-forward svc/ksmc-controller 8080 &
curl -s localhost:8080/metrics | grep '^ksmc_services'
# ksmc_services{phase="Running",task="lobby"} 2.0
```

## Métriques des services

Chaque service expose `GET /metrics` sur son port de santé **8090** (`ksmc_service_*`, labels `namespace`,
`service`, `task`) :

| Métrique | Description |
|---|---|
| `ksmc_service_up` | 1 si le process du serveur tourne |
| `ksmc_service_uptime_seconds` | temps depuis le lancement |
| `ksmc_service_bridge_connected` | 1 si un rapport récent (< 60 s) du bridge est disponible |
| `ksmc_service_players`, `ksmc_service_max_players` | joueurs (bridge) |
| `ksmc_service_tps` | ticks par seconde (bridge, serveurs qui le mesurent : Paper, Fabric, NeoForge ; pas Spigot ni les proxies) |
| `ksmc_service_jvm_heap_used_bytes`, `ksmc_service_jvm_heap_max_bytes` | tas de la JVM du serveur (bridge) |
| `ksmc_service_jvm_gc_collections_total`, `ksmc_service_jvm_gc_seconds_total` | GC de la JVM du serveur (bridge) |

Les valeurs « bridge » viennent du rapport périodique (5 s) du plugin bridge installé dans le serveur ; sans bridge,
seules les trois premières métriques sont présentes.

## Prometheus Operator et Grafana

```yaml
# values.yaml
metrics:
  serviceMonitor:
    enabled: true            # ServiceMonitor (controller) + PodMonitor (services, port health)
    labels: {release: kube-prometheus-stack}   # selon le sélecteur de votre Prometheus
  prometheusRule:
    enabled: true
    lowTps: 15
  grafanaDashboard:
    enabled: true            # ConfigMap avec le label grafana_dashboard: "1" (sidecar Grafana)
```

Le tableau de bord (`deploy/helm/ksmc/grafana/ksmc.json`, importable aussi à la main) montre joueurs, services par
phase, TPS, mémoire, durée de démarrage, redémarrages, réconciliations, API et modules.

Alertes de la `PrometheusRule` :

| Alerte | Condition |
|---|---|
| `KsmcControllerDown` (critical) | controller non collecté depuis 5 min |
| `KsmcTaskDegraded` | task dégradée depuis 5 min |
| `KsmcServiceCrashLooping` | plus de 3 redémarrages d'un service en 15 min |
| `KsmcServicesFailed` | services en phase `Failed` depuis 10 min |
| `KsmcLowTps` | TPS sous `metrics.prometheusRule.lowTps` depuis 5 min |

Avec `networkPolicy.enabled`, le port 8090 des serveurs est ouvert aux namespaces de
`metrics.scrapeNamespaceSelector` (tous par défaut quand le ServiceMonitor est activé).

## Métriques des modules

```java
ModuleMetrics.Counter greetings = ctx.metrics().counter("greetings");
greetings.increment();                                    // ksmc_module_hello_greetings_total
ctx.metrics().gauge("queue_size", queue::size);           // ksmc_module_hello_queue_size
ctx.metrics().timer("sync", "target", "s3").record(d);    // ksmc_module_hello_sync_seconds
```

Préfixe `ksmc_module_<id>_` imposé, noms `[a-z][a-z0-9_]*`, métriques retirées à la désactivation du module.

## Logs

- Format : `controller.logFormat: TEXT` (défaut) ou `JSON` (une ligne JSON par événement : `timestamp`, `level`,
  `loggerName`, `message`, `mdc`, `throwable`), pour Loki, Elasticsearch, etc.
- La ressource réconciliée apparaît dans chaque ligne : `[ServiceTask/lobby]` en texte, `mdc.resource.kind` /
  `mdc.resource.name` en JSON.
- Niveau initial : `controller.logLevel`. À chaud (permission `ksmc.admin.logs`, rôle `admin`) :

```bash
TOKEN=...   # ksmc login, ou jeton d'API
curl -X PUT localhost:8080/api/v1/admin/loglevel -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"logger": "io.ksmc", "level": "DEBUG"}'
curl localhost:8080/api/v1/admin/loglevel -H "Authorization: Bearer $TOKEN"   # niveaux définis
# {"logger": "io.ksmc", "level": null} rétablit l'héritage ; sans "logger" : logger racine.
```

Le changement est audité et ne survit pas à un redémarrage (utiliser `controller.logLevel` pour un niveau durable).
