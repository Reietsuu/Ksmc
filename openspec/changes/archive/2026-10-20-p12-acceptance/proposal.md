# Proposal

## Why

Une fois toutes les capacités livrées, il faut prouver que le système complet fonctionne de bout en bout
sur un vrai cluster, publier une première version, et donner au propriétaire du projet une procédure de
test manuelle claire pour vérifier lui-même le bon fonctionnement.

## What Changes

- Suite e2e « réseau complet » : proxy Velocity + proxy BungeeCord, lobbies Paper, task Spigot legacy, serveur Fabric,
  serveur NeoForge, autoscaling, module exemple, REST, CLI, dashboard — sur kind en CI et en local.
- Test de montée de version (installation de la release précédente → mise à jour du chart).
- `docs/TESTING.md` : ordres de test manuels pas à pas (commandes à exécuter + résultat attendu) couvrant chaque capacité.
- `docs/ROADMAP.md` : fonctionnalités futures et points d'extension prévus (permissions, signs, OIDC, S3, multi-cluster, GraalVM...).
- Release `v0.1.0`.

**Hors périmètre** : nouvelles fonctionnalités. Aucune spec ne change (`skip_specs`).

## Capabilities

### New Capabilities
- (aucune — change de validation et de livraison)

### Modified Capabilities
- (aucune)

## Impact

`ksmc-e2e`, `scripts/`, `docs/`, workflows de release.
