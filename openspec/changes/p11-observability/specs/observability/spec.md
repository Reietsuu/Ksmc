# Spec Delta

## Purpose

Rendre l'état et la santé du cloud mesurables et alertables avec Prometheus, Grafana et les
logs standards de Kubernetes.

## ADDED Requirements

### Requirement: Métriques du controller
Le controller SHALL exposer sur `/metrics` (format Prometheus) : services par task et phase, joueurs en ligne par task,
durée de démarrage des services, nombre et durée des réconciliations, erreurs, état des modules et requêtes API.

#### Scenario: Scrape
- **WHEN** Prometheus interroge `/metrics` du controller
- **THEN** la métrique `ksmc_services{task="lobby",phase="Running"}` est présente avec la bonne valeur

### Requirement: Métriques des services
Chaque service SHALL exposer sur le port 8090 `/metrics` : joueurs, TPS (si bridge), mémoire et GC JVM, uptime.

#### Scenario: TPS d'un serveur Paper
- **WHEN** un service Paper avec bridge tourne
- **THEN** `ksmc_service_tps` est exposé pour ce service

### Requirement: Intégration Prometheus Operator et Grafana
Le chart SHALL pouvoir créer `ServiceMonitor`, `PodMonitor`, `PrometheusRule` (alertes : task dégradée, service en crash, controller indisponible) et un ConfigMap de tableau de bord Grafana.

#### Scenario: Activation
- **WHEN** `metrics.serviceMonitor.enabled=true`
- **THEN** les objets ServiceMonitor et PodMonitor sont créés avec les bons sélecteurs

### Requirement: Logs
Le controller SHALL écrire des logs texte ou JSON (choix par configuration) avec le nom de la ressource concernée, et son niveau SHALL être modifiable à l'exécution via l'API par un administrateur.

#### Scenario: Debug à chaud
- **WHEN** un admin passe le niveau à `DEBUG` via l'API
- **THEN** les logs DEBUG apparaissent sans redémarrage

### Requirement: Métriques de modules
Les modules SHALL pouvoir enregistrer des métriques qui apparaissent sur `/metrics` préfixées `ksmc_module_<id>_`.

#### Scenario: Compteur de module
- **WHEN** le module `hello` incrémente son compteur `greetings`
- **THEN** `ksmc_module_hello_greetings_total` augmente
