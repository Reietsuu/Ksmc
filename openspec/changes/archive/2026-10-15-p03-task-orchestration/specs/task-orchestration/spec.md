# Spec Delta

## Purpose

Maintenir automatiquement, pour chaque `ServiceTask`, le bon ensemble de `CloudService` :
nombre, noms, remplacement des échecs et application des changements de configuration.

## ADDED Requirements

### Requirement: Nombre minimum de services
Pour chaque task activée, le controller SHALL maintenir au moins `minServiceCount` services non arrêtés,
sans jamais dépasser `maxServiceCount` (sauf `-1` = illimité).

#### Scenario: Création initiale
- **WHEN** une task `lobby` avec `minServiceCount: 2` est créée
- **THEN** les services `lobby-1` et `lobby-2` sont créés et atteignent `Running`

#### Scenario: Réduction du minimum
- **WHEN** `minServiceCount` passe de 3 à 1
- **THEN** les services en trop sont arrêtés en commençant par ceux qui ont le moins de joueurs, puis le numéro le plus élevé

### Requirement: Numérotation des services
Les services d'une task SHALL être nommés `<task>-<n>` où `n` est le plus petit entier positif libre.

#### Scenario: Réutilisation d'un numéro
- **WHEN** `lobby-1` est supprimé alors que `lobby-2` tourne et que le minimum vaut 2
- **THEN** le nouveau service s'appelle `lobby-1`

### Requirement: Remplacement des services terminés
Un service dynamique en `Stopped` ou `Failed` SHALL être supprimé puis remplacé si nécessaire pour respecter le
minimum ; après 5 échecs consécutifs en moins de 10 minutes, la task SHALL passer en condition `Degraded` et espacer les tentatives (backoff exponentiel plafonné à 5 minutes).

#### Scenario: Crash en boucle
- **WHEN** chaque service d'une task échoue au démarrage
- **THEN** la task affiche la condition `Degraded=True` avec la dernière raison d'échec et les créations ralentissent

### Requirement: Services statiques
Pour une task `static: true`, les services SHALL garder leur nom et leurs données : un service statique arrêté
SHALL être redémarré sous le même nom avec le même volume ; réduire le minimum SHALL arrêter les services sans supprimer leurs volumes.

#### Scenario: Redémarrage d'un statique
- **WHEN** le Pod de `survival-1` se termine
- **THEN** `survival-1` est redémarré avec son PVC d'origine

### Requirement: Démarrage et arrêt manuels
Un utilisateur SHALL pouvoir demander N services supplémentaires (`ksmc.io/start-requests` ou API) dans la
limite de `maxServiceCount`, et arrêter un service précis même si cela passe temporairement sous le minimum, auquel cas il est remplacé.

#### Scenario: Plafond atteint
- **WHEN** l'utilisateur demande un service de plus alors que `maxServiceCount` est atteint
- **THEN** la demande est refusée avec le motif `MaxServiceCountReached`

### Requirement: Maintenance et désactivation
Une task SHALL accepter `maintenance: true` (les services restent démarrés mais refusent les joueurs non autorisés, appliqué par le bridge en p05) et `enabled: false` (tous ses services sont arrêtés).

#### Scenario: Désactivation
- **WHEN** l'utilisateur met `enabled: false` sur `lobby`
- **THEN** tous les services `lobby-*` sont arrêtés et aucun n'est recréé

### Requirement: Mise à jour de configuration
Quand la spec d'une task change, les services dynamiques existants SHALL être remplacés selon `updateStrategy` :
`Recreate`, `RollingUpdate` (par défaut, `maxUnavailable: 1`, en attendant `Running` du remplaçant) ou `OnRestart` (appliqué seulement aux prochains démarrages).

#### Scenario: Rolling update
- **WHEN** la version de Paper d'une task de 3 services passe de 1.21.3 à 1.21.4 en `RollingUpdate`
- **THEN** au plus un service est indisponible à la fois et les 3 services finissent avec 1.21.4

### Requirement: Statut agrégé
`ServiceTask.status` SHALL indiquer le nombre de services par phase, `readyServices`, `onlinePlayers` cumulés, et la génération appliquée.

#### Scenario: kubectl get
- **WHEN** 2 services sur 3 sont `Running`
- **THEN** `kubectl get mctask lobby` affiche `READY 2/3`

### Requirement: Décision de mise à l'échelle extensible
Le nombre cible de services SHALL être calculé par une stratégie remplaçable ; la stratégie par défaut retourne
le maximum entre `minServiceCount` et les demandes manuelles, borné par `maxServiceCount`.

#### Scenario: Stratégie par défaut
- **WHEN** aucune autre stratégie n'est configurée sur la task
- **THEN** le nombre de services suit exactement `minServiceCount` plus les démarrages manuels
