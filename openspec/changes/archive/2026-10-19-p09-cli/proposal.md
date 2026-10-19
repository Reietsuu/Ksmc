# Proposal

## Why

Les administrateurs CloudNet travaillent surtout dans la console du node. Ksmc doit offrir une CLI
`ksmc` équivalente, utilisable depuis n'importe quel poste, scriptable, et extensible par les modules.

## What Changes

- CLI `ksmc` (Java 21, picocli) parlant à l'API REST : contextes multiples, login, commandes pour tasks,
  groupes, templates, services (dont console interactive), joueurs, modules, utilisateurs, événements.
- Sorties tableau, JSON et YAML ; codes de sortie fiables pour les scripts.
- Commandes de modules découvertes dynamiquement.
- Distribution : JAR exécutable + scripts de lancement, et image conteneur ; complétion shell.

**Hors périmètre** : binaire natif GraalVM (laissé ouvert, ajout possible dans `release.yml`).

## Capabilities

### New Capabilities
- `cli` : interface en ligne de commande de gestion du cloud.

### Modified Capabilities
- (aucune)

## Impact

Nouveau projet `ksmc-cli`, artefacts de release.
