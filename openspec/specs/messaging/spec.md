# messaging Specification

## Purpose
Permettre à tout composant Ksmc (controller, modules, wrapper, plugins sur les serveurs et proxies)
d'échanger simplement des messages typés, avec ciblage, réponses et stockage partagé.

## Requirements

### Requirement: Message de channel
Un message SHALL contenir : `channel`, `message` (type), `data` (JSON libre), `sender` (type + nom du composant),
`target`, un identifiant unique et la version du protocole.

#### Scenario: Diffusion simple
- **WHEN** un plugin de `lobby-1` envoie sur le channel `chat` le message `broadcast` à la cible `all`
- **THEN** tous les abonnés au channel `chat` dans le namespace reçoivent le message avec `sender = service/lobby-1`

### Requirement: Ciblage
La cible SHALL pouvoir être `all`, `service:<nom>`, `task:<nom>`, `environment:<proxy|server>` ou `controller` ;
seuls les composants correspondants SHALL recevoir le message.

#### Scenario: Ciblage d'une task
- **WHEN** un module envoie un message à `task:bedwars`
- **THEN** seuls les services de la task `bedwars` le reçoivent

### Requirement: Requête et réponse
L'émetteur SHALL pouvoir envoyer une requête et attendre une réponse jusqu'à un timeout (défaut 5 s) ; sans réponse,
il SHALL recevoir une erreur de timeout explicite.

#### Scenario: Requête sans répondeur
- **WHEN** une requête est envoyée sur un channel sans abonné avec un timeout de 1 s
- **THEN** l'émetteur reçoit une erreur `NoResponders` ou `Timeout` en moins de 2 s

### Requirement: Isolation par namespace
Les messages SHALL être isolés par namespace Kubernetes : un composant ne reçoit jamais les messages d'un autre namespace.

#### Scenario: Deux installations
- **WHEN** deux namespaces `prod` et `test` utilisent le même serveur NATS
- **THEN** un message `all` envoyé dans `test` n'est pas reçu dans `prod`

### Requirement: Droits des services
Les credentials des services SHALL interdire de publier des événements cloud ou des commandes console ; seul le
controller (et ses modules) SHALL pouvoir le faire.

#### Scenario: Usurpation
- **WHEN** un plugin tente de publier un événement `service.started` falsifié
- **THEN** la publication est refusée par le transport

### Requirement: Stockage clé-valeur partagé
Le système SHALL fournir des buckets clé-valeur nommés (lire, écrire, supprimer, comparer-et-écrire, surveiller
les changements), avec TTL optionnel, accessibles aux modules et plugins.

#### Scenario: Compteur partagé
- **WHEN** deux services font un comparer-et-écrire concurrent sur la même clé
- **THEN** un seul réussit et l'autre reçoit un conflit de révision

### Requirement: Transport remplaçable
Le transport SHALL être sélectionnable par configuration ; NATS est le transport par défaut et un module SHALL pouvoir fournir un autre transport.

#### Scenario: Transport par défaut
- **WHEN** aucune configuration de transport n'est fournie
- **THEN** controller et services utilisent NATS à l'URL fournie par le chart

### Requirement: Commandes console par messagerie
Le controller SHALL pouvoir envoyer une commande console à un service ; le wrapper SHALL l'écrire sur l'entrée standard du serveur et accuser réception.

#### Scenario: Commande à distance
- **WHEN** le controller envoie `say hi` à `lobby-1`
- **THEN** le serveur `lobby-1` exécute la commande et le controller reçoit l'accusé
