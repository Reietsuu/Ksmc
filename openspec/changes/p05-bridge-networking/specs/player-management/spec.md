# Spec Delta

## Purpose

Savoir en permanence quels joueurs sont connectés, sur quel proxy et quel serveur, et pouvoir agir
sur eux depuis n'importe quelle interface ou module.

## ADDED Requirements

### Requirement: Registre des joueurs
Le système SHALL maintenir pour chaque joueur connecté : UUID, nom, proxy, serveur actuel, heure de connexion, version de protocole.

#### Scenario: Changement de serveur
- **WHEN** Steve passe de `lobby-1` à `game-2`
- **THEN** le registre indique `game-2` comme serveur actuel de Steve en moins de 2 s

### Requirement: Événements joueurs
Le système SHALL publier `player.login`, `player.server-switch` et `player.disconnect`.

#### Scenario: Déconnexion
- **WHEN** Steve se déconnecte
- **THEN** un événement `player.disconnect` est publié et Steve disparaît du registre

### Requirement: Actions sur les joueurs
Le système SHALL permettre : envoyer un joueur vers un service ou une task, l'expulser du réseau, lui envoyer un message ou un titre.

#### Scenario: Envoi vers une task
- **WHEN** un module envoie Steve vers la task `bedwars`
- **THEN** Steve est connecté au service `bedwars` le moins rempli parmi ceux en `Running`

### Requirement: Cohérence après panne
Quand un proxy disparaît, ses joueurs SHALL être retirés du registre en moins de 30 s.

#### Scenario: Proxy tué
- **WHEN** le Pod de `proxy-1` est tué brutalement
- **THEN** ses joueurs ne figurent plus dans le registre 30 s plus tard
