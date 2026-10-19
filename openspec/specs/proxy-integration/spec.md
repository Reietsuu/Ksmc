# proxy-integration Specification

## Purpose
Faire des proxies (Velocity, BungeeCord, Waterfall) la porte d'entrée dynamique du réseau :
ils suivent les serveurs du cloud et routent les joueurs vers les bons.

## Requirements

### Requirement: Enregistrement dynamique des serveurs
Un proxy SHALL ajouter à sa liste chaque service `server` du namespace dès qu'il est `Running` et le retirer
dès qu'il passe `Stopping`, `Stopped`, `Failed` ou est supprimé ; au démarrage il SHALL charger la liste actuelle.

#### Scenario: Nouveau serveur
- **WHEN** `game-3` atteint `Running`
- **THEN** en moins de 5 s `game-3` apparaît dans `/server` sur tous les proxies

#### Scenario: Proxy redémarré
- **WHEN** un proxy démarre alors que 4 serveurs tournent déjà
- **THEN** il connaît les 4 serveurs avant d'accepter le premier joueur

### Requirement: Fallback et lobby
Une task serveur SHALL pouvoir être marquée `fallback` avec une priorité et une permission optionnelle ; à la connexion
ou à l'expulsion d'un serveur, le joueur SHALL être envoyé sur le service fallback `Running` le moins rempli de la priorité la plus haute qui lui est accessible.

#### Scenario: Connexion initiale
- **WHEN** un joueur se connecte et que `lobby-1` (8 joueurs) et `lobby-2` (3 joueurs) sont fallbacks
- **THEN** le joueur arrive sur `lobby-2`

#### Scenario: Aucun fallback
- **WHEN** aucun service fallback n'est `Running`
- **THEN** le joueur est déconnecté avec un message configurable

### Requirement: Forced hosts
Une task proxy SHALL pouvoir associer un nom d'hôte à une task serveur cible (`forcedHosts`).

#### Scenario: Hôte dédié
- **WHEN** un joueur se connecte via `bedwars.example.com` associé à la task `bedwars`
- **THEN** il est envoyé sur un service de `bedwars`

### Requirement: Maintenance et limite
Une task proxy en maintenance SHALL refuser les joueurs hors liste blanche avec un motd de maintenance ;
le proxy SHALL appliquer le nombre maximum de joueurs global configuré.

#### Scenario: Maintenance
- **WHEN** la task `proxy` est en maintenance et qu'un joueur non autorisé se connecte
- **THEN** il est refusé avec le message de maintenance

### Requirement: Liste de joueurs globale
Le ping du proxy SHALL afficher le nombre de joueurs de tout le réseau et le maximum configuré.

#### Scenario: Deux proxies
- **WHEN** 10 joueurs sont sur `proxy-1` et 5 sur `proxy-2`
- **THEN** le ping de chaque proxy affiche 15 joueurs en ligne
