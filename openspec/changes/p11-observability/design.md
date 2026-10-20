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
2. Wrapper (Java 8) : exposition Prometheus texte écrite à la main (pas de dépendance) sur `/metrics` du port de
   santé 8090. Le serveur tourne dans une JVM enfant : c'est le bridge qui mesure la JVM du serveur (tas, GC, via
   `ManagementFactory`) et l'ajoute à son `ServiceReport` (joueurs, TPS) ; le wrapper s'abonne au sujet NATS du
   rapport de son propre service (déjà connecté pour la console) plutôt qu'à un socket local. Sans bridge (ou rapport
   de plus de 60 s) : seulement `ksmc_service_up`, `ksmc_service_uptime_seconds`, `ksmc_service_bridge_connected`.
3. Logs JSON avec le `JsonEncoder` intégré à Logback 1.6 (pas de `logstash-logback-encoder` : aucune dépendance
   ajoutée), choisi par `KSMC_LOG_FORMAT` (`controller.logFormat` du chart). La ressource vient du MDC posé par JOSDK
   (`resource.kind`, `resource.name`). Niveau via `GET/PUT /api/v1/admin/loglevel`, nouvelle permission
   `ksmc.admin.logs` (rôle admin), action auditée, non persistée.
4. Dashboard Grafana JSON versionné dans le chart (`deploy/helm/ksmc/grafana/ksmc.json`, Helm ne lit que les
   fichiers du chart), importable tel quel ; ConfigMap avec le label `grafana_dashboard: "1"` (sidecar Grafana).
   Le Service du controller porte `app.kubernetes.io/component: controller` pour le sélecteur du ServiceMonitor.
