# Proposal

## Why

La communication entre services, proxies, controller et modules est ce qui rend un cloud Minecraft
utile (envoyer un joueur, synchroniser un état, notifier un événement). Elle doit être simple à utiliser
depuis un plugin ou un module et indépendante du transport.

## What Changes

- Protocole JSON versionné (`ksmc-wire`, Java 8) : messages de channel, requêtes/réponses, événements.
- Transport NATS par défaut (`ksmc-messaging-nats`), derrière l'interface `MessagingTransport`.
- Channels nommés avec ciblage (tous, un service, une task, un environnement, le controller).
- Requête/réponse avec timeout.
- Événements cloud publiés par le controller (cycle de vie des services/tasks) et abonnement filtré.
- Stockage clé-valeur partagé (NATS KV) exposé comme API simple pour les modules et plugins.
- Commandes console transmises au wrapper par la messagerie.

**Hors périmètre** : API côté plugins (p06 `plugin-api`), événements joueurs (p05).
**Laissé ouvert** : autres transports (Redis, Kafka) via module.

## Capabilities

### New Capabilities
- `messaging` : échange de messages entre composants (channels, ciblage, requête/réponse, KV).
- `cloud-events` : événements du cycle de vie publiés par le cloud et leur abonnement.

### Modified Capabilities
- (aucune)

## Impact

Nouveaux projets `ksmc-wire`, `ksmc-messaging-nats` ; le controller et le wrapper se connectent à NATS ;
chart : comptes NATS (controller vs services), bucket KV.
