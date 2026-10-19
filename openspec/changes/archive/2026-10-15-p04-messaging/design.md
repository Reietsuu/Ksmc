# Design

## Context

NATS est déployé par le chart depuis p01. Le wrapper (Java 8) et les bridges doivent embarquer le client.

## Goals / Non-Goals

**Goals :** API minimale et stable ; un seul format sur le fil ; sécurité par comptes NATS.
**Non-Goals :** garanties exactly-once (at-most-once pour channels, at-least-once pour événements via JetStream).

## Decisions

1. **Sujets** : `ksmc.<ns>.ch.<channel>.<targetType>.<targetName>` (ex. `ksmc.prod.ch.chat.task.bedwars`) ;
   chaque composant s'abonne aux variantes qui le concernent (`all.*`, `service.<lui>`, `task.<sa task>`, `env.<son env>`).
   Le filtrage est donc fait par NATS et non côté client.
2. **Événements** : stream JetStream `KSMC_EVENTS_<ns>` (sujets `ksmc.<ns>.ev.>`, rétention 10 min, `max_age`), consommateurs
   éphémères ordonnés pour rejouer depuis une séquence/heure.
3. **RPC** : request/reply NATS natif ; sujets `ksmc.<ns>.rpc.<service>.<method>` ; erreurs encodées dans l'en-tête `Ksmc-Error`.
4. **KV** : NATS KV, buckets `ksmc_<ns>_<nom>`, CAS par révision.
5. **Sécurité** : deux utilisateurs NATS (comptes dans la config du chart) : `controller` (tout `ksmc.>`) et `service`
   (publication interdite sur `ksmc.*.ev.>` et `ksmc.*.cmd.>`). Le mot de passe `service` est injecté dans les Pods via Secret.
   Évolution prévue : comptes par namespace / nkeys.
6. **JSON** : Gson dans `ksmc-wire` (Java 8, léger) ; champ `v` de version du protocole ; champs inconnus ignorés (compatibilité ascendante).
7. **Client NATS** : `io.nats:jnats` 2.x (compatible Java 8), relocalisé (shadow) dans les JARs de plugins pour éviter les conflits.

## Risks / Trade-offs

- [Isolation par namespace basée sur la convention de sujets, pas sur des comptes séparés] → acceptable en v1, noté pour durcissement.
- [Taille des plugins (+~600 Ko)] → acceptable.
