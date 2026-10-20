# Design

## Decisions

1. Micrometer + `micrometer-registry-prometheus` 1.16 dans le controller (dépendance : format Prometheus standard,
   métriques JVM, histogrammes). Les modules n'y ont pas accès directement : `ModuleContext.metrics()` renvoie une
   interface de `ksmc-api` (`ModuleMetrics` : compteur, jauge, durée) qui impose le préfixe `ksmc_module_<id>_` et
   retire les métriques à la désactivation — l'API publique ne dépend pas de Micrometer.
   L'état des ressources (services par task/phase, joueurs, tasks dégradées, redémarrages, modules) est relu à la
   collecte (au plus toutes les 5 s) ; les réconciliations passent par l'interface `Metrics` de JOSDK ; les requêtes
   API sont étiquetées par route déclarée (pas le chemin concret) pour borner la cardinalité. `/metrics` est servi
   sans authentification sur le port HTTP du controller (8080), comme `/healthz`.
2. Wrapper (Java 8) : exposition Prometheus texte écrite à la main (pas de dépendance), données JVM via JMX, TPS reçu du bridge par socket local.
3. Logback avec encodeur JSON (`logstash-logback-encoder`) activable ; niveau via `PUT /api/v1/admin/loglevel`.
4. Dashboard Grafana JSON versionné dans `deploy/grafana/`.
