# Proposal

## Why

Un proxy qui crashe net (JVM tuée, mémoire épuisée) coupe forcément ses joueurs : leur connexion se termine dans le
process. Mais le cas le plus fréquent est un proxy **figé** : un plugin bloque les événements ou le planificateur,
plus personne ne peut se connecter ni changer de serveur, alors que le ping répond encore et que Kubernetes le croit
sain. Les joueurs restent coincés jusqu'à une intervention manuelle. Un chien de garde peut détecter ce gel,
sortir le proxy du Service, transférer ses joueurs vers un proxy sain (mécanisme de p13) puis le redémarrer.

## What Changes

- **Chien de garde dans le bridge proxy** (Velocity d'abord) : un thread indépendant vérifie que le planificateur
  et le bus d'événements du proxy répondent. Au-delà de `ksmc.watchdog.timeout-seconds` (défaut 30) sans réponse,
  le proxy est déclaré figé.
- **Réaction au gel** : le proxy est retiré du Service (la sonde `/ready` passe à 503), ses joueurs 1.20.5+ sont
  transférés vers un autre proxy de la task (repli : message), les threads sont écrits dans les logs pour le
  diagnostic, puis le process est arrêté pour que le service redémarre.
- **Sondes du runtime** : `/ready` et `/live` tiennent compte de l'état signalé par le bridge (fichier d'état),
  pour que Kubernetes arrête d'envoyer des joueurs et relance un proxy figé même si le ping répond.
- Événement cloud `service.frozen` et métrique `ksmc_service_frozen_total` pour suivre les gels.

**Hors périmètre** : crash net du process (impossible de garder les connexions : le joueur se reconnecte, le Service
l'envoie sur un proxy sain, la session orpheline de p13 ne le bloque pas) ; chien de garde des serveurs de jeu ;
BungeeCord (change ultérieur, même cœur).
**Laissé ouvert** : seuils adaptatifs ; redémarrage « à chaud » sans perte (non faisable avec le protocole Minecraft).

**Dépendance** : p13-multiproxy (plan de transfert et drainage) doit être mergé avant l'implémentation.

## Capabilities

### New Capabilities
- `proxy-watchdog` : détection d'un proxy figé et réaction (sortie du Service, transfert des joueurs, redémarrage).

### Modified Capabilities
- `service-runtime` : la santé (`/ready`, `/live`) intègre l'état « figé » signalé par le bridge.

## Impact

- `ksmc-bridge-common` : `Watchdog` (Java 8, thread démon, horloge injectable pour les tests), écriture du fichier d'état.
- `ksmc-bridge-velocity` : battements via `Scheduler` et `EventManager`, drainage réutilisé de p13.
- `ksmc-wrapper` : `HealthServer` lit le fichier d'état ; arrêt forcé du process après le drainage.
- `ksmc-controller` : événement `service.frozen`, métrique. Docs : USAGE, OBSERVABILITY, TESTING. Aucune dépendance nouvelle.
