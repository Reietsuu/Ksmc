# Tasks

Prérequis : p13-multiproxy mergé dans `main` (rebaser `feat/p14-proxy-watchdog` dessus avant la tâche 1.1).

## 1. Détection

- [x] 1.1 `Watchdog` (commun) : battements `scheduler`/`events`, horloge injectable, seuil `ksmc.watchdog.timeout-seconds`, désactivation, pas de contrôle avant le premier battement ; tests unitaires ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [x] 1.2 Velocity : tâche `Scheduler` (1 s) et `KsmcHeartbeatEvent` via `EventManager.fire()` (5 s), démarrage du thread du chien de garde ; vérifier `./gradlew :ksmc-bridge-velocity:build -q`
- [x] 1.3 Commit + push (`feat(bridge): proxy watchdog detection`)

## 2. Réaction

- [x] 2.1 Fichier d'état `.ksmc/frozen` écrit par le bridge ; `HealthServer` : `/ready` 503 si présent, `/live` 503 après le délai ; suppression au démarrage du wrapper ; tests (`HealthServerTest`) ; vérifier `./gradlew :ksmc-wrapper:test -q`
- [x] 2.2 Séquence après gel : attente de sortie du Service, `drain()` + exécution avec délai global, vidage des threads, `Runtime.halt(70)` (plateforme abstraite pour les tests) ; tests unitaires ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [x] 2.3 Commit + push (`feat(bridge): drain and restart a frozen proxy`)

## 3. Visibilité et vérification

- [ ] 3.1 `ServiceReport.frozen` (envoi immédiat) ; controller : événement `service.frozen` et métrique `ksmc_service_frozen_total{task}` ; tests ; vérifier `./gradlew :ksmc-wire:test :ksmc-controller:test -q`
- [ ] 3.2 Commande de test `ksmc:freeze-test` (seulement avec `ksmc.watchdog.test: true`) ; E2E : gel provoqué, `service.frozen`, sortie du Service, repli du bot 1.8, redémarrage ; vérifier en CI (`gh run watch`)
- [ ] 3.3 Docs : USAGE (propriétés `ksmc.watchdog.*`, limites : crash net), OBSERVABILITY (événement, métrique), TESTING ; vérifier les liens
- [ ] 3.4 Commit + push (`feat(observability): frozen proxy event and metric`)

## Workflow follow-up

- Merger `feat/p14-proxy-watchdog` une fois la CI verte, puis `openspec archive p14-proxy-watchdog`.
