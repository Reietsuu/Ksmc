# Proposal

## Why

Beaucoup d'administrateurs de serveurs Minecraft préfèrent une interface graphique. Un dashboard web
rend le cloud gérable sans terminal et donne une vue d'ensemble en temps réel.

## What Changes

- Application web (React + TypeScript + Vite) servie par le controller sur `/`.
- Pages : vue d'ensemble, tasks (création/édition par formulaire ou YAML), groupes, services (console intégrée),
  templates (explorateur et éditeur de fichiers), logiciels, joueurs, modules, utilisateurs/jetons, audit.
- Mises à jour en temps réel par le flux d'événements.
- Pages de modules chargées dynamiquement ; thème clair/sombre ; interface en français avec i18n prête pour d'autres langues.

**Hors périmètre** : application mobile native.

## Capabilities

### New Capabilities
- `web-dashboard` : interface web de gestion du cloud.

### Modified Capabilities
- (aucune)

## Impact

Nouveau dossier `dashboard/`, intégration au build Gradle et à l'image du controller.
