# Design

## Context

Repose sur `CloudService` (p02). Voir `docs/ARCHITECTURE.md`.

## Goals / Non-Goals

**Goals :** comportement proche de CloudNet, idempotent, robuste aux redémarrages du controller.
**Non-Goals :** autoscaling par joueurs (p07).

## Decisions

1. **Les `CloudService` sont possédés par leur task** (ownerReference, `blockOwnerDeletion`). Pas de Deployment/StatefulSet :
   on a besoin de choisir *quel* service arrêter (moins de joueurs) et de numéros réutilisés, que les contrôleurs natifs ne permettent pas.
   Les PVC statiques ne sont **pas** possédés (survivent à la suppression de la task).
2. **Hash de configuration effective** (`ksmc.io/config-hash` sur chaque service) : détecte les services obsolètes pour l'`updateStrategy`.
3. **Demandes manuelles** : compteur `spec.extraServices` géré par l'API (et annotation `ksmc.io/start-requests` pour kubectl) ;
   l'arrêt explicite d'un service le marque `desiredState: Stopped` puis il est supprimé et remplacé si nécessaire.
4. **SPI `ScalingStrategy`** : `int desiredCount(TaskView task, List<ServiceView> services)` ; choisie par `spec.autoscaling.strategy` (défaut `static`).
5. **Indexation** : informers JOSDK avec index par label `ksmc.io/task` et sources secondaires (`ServiceGroup` → tasks concernées).
6. **Backoff** : état stocké dans `status.failureWindow` pour survivre aux redémarrages du controller.

## Risks / Trade-offs

- [Course entre deux réconciliations créant le même nom] → création avec nom fixe : un conflit 409 relance la réconciliation.
- [Rolling update bloqué si le nouveau service ne démarre jamais] → `progressDeadlineSeconds` (défaut 600) puis condition `Progressing=False`.
