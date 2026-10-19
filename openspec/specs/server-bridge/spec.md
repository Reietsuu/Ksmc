# server-bridge Specification

## Purpose
Intégrer chaque serveur de jeu au cloud : remonter son état réel et exécuter les actions demandées,
pour Spigot/Paper, Fabric et NeoForge.

## Requirements

### Requirement: Installation automatique
Le bridge adapté au logiciel SHALL être ajouté automatiquement aux services, sauf si la task indique `bridge.enabled: false` ;
sans bridge, le service SHALL fonctionner quand même (état par ping).

#### Scenario: Sans bridge
- **WHEN** une task Forge désactive le bridge
- **THEN** ses services atteignent `Running` et leur nombre de joueurs vient du Server List Ping

### Requirement: Remontée d'état
Le bridge SHALL publier, au moins toutes les 5 s et à chaque changement : joueurs en ligne et max, motd,
état personnalisé (`state`, ex. `LOBBY`, `INGAME`), TPS si disponible, et propriétés libres définies par les plugins.

#### Scenario: État personnalisé
- **WHEN** un plugin de mini-jeu met l'état à `INGAME`
- **THEN** `CloudService.status.state` vaut `INGAME` en moins de 5 s

### Requirement: Actions sur le serveur
Le bridge SHALL exécuter les actions reçues : commande console, message/titre à un joueur, expulsion d'un joueur.

#### Scenario: Expulsion
- **WHEN** le cloud demande l'expulsion de `Steve` avec un motif
- **THEN** Steve est déconnecté du serveur avec ce motif

### Requirement: Compatibilité des versions
Le bridge Bukkit SHALL fonctionner de Minecraft 1.8.8 à la dernière version supportée par Paper ; les bridges Fabric et
NeoForge SHALL cibler au moins la dernière version Minecraft stable au moment de la release.

#### Scenario: Serveur 1.8.8
- **WHEN** un service Spigot 1.8.8 démarre avec le bridge
- **THEN** le bridge se charge sans erreur et son état est remonté
