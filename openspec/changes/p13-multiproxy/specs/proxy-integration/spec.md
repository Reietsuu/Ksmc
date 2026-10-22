## MODIFIED Requirements

### Requirement: Liste de joueurs globale
Le ping du proxy SHALL afficher le nombre de joueurs de tout le réseau et le maximum configuré. Une task proxy SHALL
pouvoir limiter ce compteur aux proxies de sa propre task avec la propriété `ksmc.player-count.scope: task`
(défaut `network`).

#### Scenario: Deux proxies
- **WHEN** 10 joueurs sont sur `proxy-1` et 5 sur `proxy-2`
- **THEN** le ping de chaque proxy affiche 15 joueurs en ligne

#### Scenario: Compteur limité à la task
- **WHEN** 10 joueurs sont sur la task `proxy-eu` et 5 sur la task `proxy-us`, et que `proxy-us` définit `ksmc.player-count.scope: task`
- **THEN** le ping de `proxy-us` affiche 5 joueurs et celui de `proxy-eu` en affiche 15

## ADDED Requirements

### Requirement: Plusieurs tasks proxy
Un namespace SHALL pouvoir contenir plusieurs tasks proxy actives. Chacune SHALL avoir sa propre exposition, ses
forced hosts, sa maintenance et son maximum de joueurs ; toutes SHALL partager les serveurs du namespace et le
registre des joueurs.

#### Scenario: Maintenance d'une seule task
- **WHEN** la task `proxy-eu` est en maintenance et pas la task `proxy-us`
- **THEN** un joueur non autorisé est refusé sur `proxy-eu` et accepté sur `proxy-us`, qui l'envoie sur un lobby partagé

#### Scenario: Expositions distinctes
- **WHEN** `proxy-eu` déclare `expose.nodePort: 30565` et `proxy-us` `expose.nodePort: 30566`
- **THEN** chaque port mène uniquement aux proxies de sa task
