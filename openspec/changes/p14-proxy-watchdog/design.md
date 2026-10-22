# Design

## Context

Après p13 : `KsmcBridge.drain()` et `TransferPlan` savent transférer/expulser les joueurs d'un proxy ; Velocity les
exécute via `Player.transferToHost`. Le wrapper (`HealthServer`) expose `/live` (process vivant) et `/ready`
(Server List Ping, cache 2 s) ; le Pod a une readiness (5 s × 2) et une liveness (10 s × 3). Le ping est servi par
les boucles réseau de Velocity : il répond souvent alors que le bus d'événements ou le planificateur sont bloqués.
Le bridge (même JVM que Velocity) et le wrapper (process parent) partagent le dossier du serveur. Les services ne
peuvent pas publier d'événements cloud (p04) : seul le controller publie `service.*`.

## Goals / Non-Goals

**Goals :** détecter un gel du proxy sans dépendre de ses threads ; le sortir du Service avant tout ; sauver les
joueurs qui peuvent l'être ; redémarrer vite ; logique testable avec une horloge simulée.
**Non-Goals :** survivre à un crash net ; chien de garde des serveurs de jeu ; BungeeCord.

## Decisions

1. **Battements.** `Watchdog` (commun, Java 8) tient deux horodatages : `scheduler` et `events`. L'adaptateur
   Velocity les rafraîchit par une tâche `Scheduler` toutes les secondes et par un événement interne
   `KsmcHeartbeatEvent` déclenché toutes les 5 s via `EventManager.fire()` (qui passe par les exécuteurs des
   plugins, là où les gels se produisent). Un thread démon du bridge vérifie toutes les secondes ; gel = un des
   deux battements plus vieux que `ksmc.watchdog.timeout-seconds` (défaut 30). Pas de vérification avant le
   premier battement (démarrage), ni pendant l'arrêt normal.
   *Alternative écartée* : se baser sur le ping, qui répond justement pendant la plupart des gels.
2. **Signal au wrapper : fichier d'état.** Le bridge écrit `.ksmc/frozen` (horodatage + raison). `HealthServer`
   répond 503 sur `/ready` dès que le fichier existe (le Pod sort des EndpointSlices en ≤ 10 s) et sur `/live`
   si le process tourne encore `ksmc.watchdog.drain-timeout-seconds` (défaut 10) + 5 s après. Le wrapper supprime
   le fichier au démarrage. *Alternative écartée* : un appel HTTP du bridge vers le wrapper, qui demande un port de
   plus et pourrait échouer pendant le gel, alors que l'écriture d'un fichier ne peut pas bloquer.
3. **Séquence après détection** (thread du chien de garde, jamais un thread de Velocity) : écrire le fichier ;
   attendre la sortie du Service (`ksmc.transfer.delay-ms` de p13, défaut 1500 ms, + 10 s de readiness) ; appeler
   `drain()` puis exécuter le plan avec un délai global `drain-timeout-seconds` (les écritures réseau sont
   asynchrones, un appel qui ne revient pas est abandonné) ; écrire un vidage des threads dans les logs ; terminer
   la JVM par `Runtime.halt(70)` (un `System.exit` resterait coincé derrière un hook bloqué). Le wrapper voit un
   code non nul ; le service est relancé comme après un crash (comportement existant).
4. **Événement et métrique.** Le rapport du bridge (`ServiceReport`) gagne le champ `frozen` (envoyé tout de suite,
   hors du cycle de 5 s). Le controller publie `service.frozen` (sujet = service, `reason`) et incrémente
   `ksmc_service_frozen_total{task}`. Champ nouveau et optionnel : compatible avec les anciens bridges.
5. **Tests.** Unitaires : `Watchdog` avec horloge injectable (gel, retard toléré, démarrage, désactivation,
   séquence et délai global avec une fausse plateforme), `HealthServer` avec fichier d'état. E2E : un plugin de test
   ou la console envoie une commande cachée `ksmc:freeze-test` (activée seulement par `ksmc.watchdog.test: true`)
   qui bloque le bus d'événements ; on vérifie `service.frozen`, la sortie du Service, le message de repli pour le
   bot 1.8 et le redémarrage du proxy.

## Risks / Trade-offs

- **Faux positif** (GC très long, nœud surchargé) → 30 s par défaut, réglable ; l'effet d'un faux positif est un
  redémarrage avec transfert, pas une coupure franche pour les clients 1.20.5+.
- **Commande de test en production** → désactivée sauf `ksmc.watchdog.test: true`, documentée comme réservée aux tests.
- **Gel des boucles réseau** → les transferts échouent, les joueurs sont coupés comme lors d'un crash ; on gagne
  quand même le redémarrage automatique et la sortie du Service.
