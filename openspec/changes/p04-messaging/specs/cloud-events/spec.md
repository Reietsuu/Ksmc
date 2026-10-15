# Spec Delta

## Purpose

Informer en temps réel les modules, plugins et interfaces de ce qui se passe dans le cloud
(services, tasks, joueurs, modules) via des événements typés et filtrables.

## ADDED Requirements

### Requirement: Événements de cycle de vie
Le controller SHALL publier `service.created`, `service.phase-changed` (ancienne et nouvelle phase),
`service.ready`, `service.stopped`, `service.deleted`, `task.updated` et `task.degraded` avec un instantané de la ressource.

#### Scenario: Service prêt
- **WHEN** `lobby-1` atteint `Running`
- **THEN** un événement `service.ready` contenant le nom, la task, l'environnement et l'adresse est publié

### Requirement: Abonnement filtré
Un abonné SHALL pouvoir filtrer par type d'événement (avec joker, ex. `service.*`), par task et par environnement.

#### Scenario: Filtre par environnement
- **WHEN** un proxy s'abonne à `service.*` pour l'environnement `server`
- **THEN** il ne reçoit pas les événements des autres proxies

### Requirement: Rattrapage
Les événements SHALL être conservés au moins 10 minutes, et un abonné SHALL pouvoir rejouer depuis un horodatage ou un numéro de séquence.

#### Scenario: Module redémarré
- **WHEN** un module redémarre et demande les événements depuis sa dernière séquence connue
- **THEN** il reçoit tous les événements manqués dans l'ordre

### Requirement: Événements personnalisés
Les modules SHALL pouvoir publier leurs propres types d'événements sous leur préfixe (`module.<id>.*`) et tout abonné SHALL pouvoir les recevoir.

#### Scenario: Événement de module
- **WHEN** le module `stats` publie `module.stats.record`
- **THEN** un abonné à `module.stats.*` le reçoit
