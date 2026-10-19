# Proposal

## Why

Un réseau Minecraft a une charge très variable (soirées, événements). Comme le SmartModule de CloudNet,
Ksmc doit démarrer des services quand les joueurs arrivent et arrêter ceux qui sont vides, sans intervention.

## What Changes

- Stratégie de scaling `players` : démarrage quand le taux de remplissage dépasse un seuil, arrêt des services vides après un délai.
- Services préparés (« warm pool ») : services prêts en avance qui ne reçoivent pas de joueurs tant qu'ils ne sont pas promus.
- Bornes `minServiceCount`/`maxServiceCount` toujours respectées, protection contre les oscillations.
- Stratégie `schedule` : minimum différent selon des plages horaires (cron).
- Décisions de scaling visibles (événements et statut).

**Hors périmètre** : scaling des nœuds du cluster (Cluster Autoscaler/Karpenter s'en chargent, compatibles car les Pods déclarent leurs ressources).
**Laissé ouvert** : stratégies personnalisées par module (SPI `ScalingStrategy` de p03).

## Capabilities

### New Capabilities
- `autoscaling` : ajustement automatique du nombre de services d'une task selon la charge et l'horaire.

### Modified Capabilities
- (aucune)

## Impact

Stratégies dans `ksmc-controller`, champs `spec.autoscaling` de `ServiceTask`, filtrage des services préparés dans les bridges proxy.
