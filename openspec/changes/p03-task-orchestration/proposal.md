# Proposal

## Why

Avec CloudNet, on ne crée presque jamais un service à la main : on déclare une task (« Lobby, au moins 2 »)
et le cloud maintient les instances. p02 sait exécuter un `CloudService` ; il faut maintenant que les
`ServiceTask` et `ServiceGroup` créent, numérotent, remplacent et arrêtent ces services automatiquement.

## What Changes

- Reconciler `ServiceTask` : maintien de `minServiceCount`, plafond `maxServiceCount`, numérotation
  `<task>-<n>` réutilisant le plus petit numéro libre, remplacement des services en échec, services statiques persistants.
- Démarrage/arrêt manuels de services supplémentaires (au-delà du minimum, dans la limite du maximum).
- Mode maintenance et arrêt complet d'une task (`enabled: false`).
- `ServiceGroup` : configuration partagée fusionnée dans les tasks qui y font référence, groupes d'environnement globaux.
- Mise à jour progressive : modifier une task remplace les services dynamiques selon une stratégie configurable.
- Statut agrégé de la task (services par phase, prêts, joueurs).

**Hors périmètre** : autoscaling selon les joueurs (p07) — ce change ne fournit que le point d'entrée `ScalingStrategy`.
**Laissé ouvert** : stratégies de mise à jour supplémentaires via SPI.

## Capabilities

### New Capabilities
- `task-orchestration` : gestion automatique du nombre, du nommage, du remplacement et de la mise à jour des services d'une task.
- `service-groups` : configuration partagée entre tasks et sa règle de fusion.

### Modified Capabilities
- (aucune)

## Impact

Reconcilers `ServiceTask` et `ServiceGroup` dans `ksmc-controller` ; SPI `ScalingStrategy` dans `ksmc-api`.
