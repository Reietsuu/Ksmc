# Proposal

## Why

kubectl suffit aux administrateurs Kubernetes, mais la CLI, le dashboard, les outils tiers et les
modules ont besoin d'une API HTTP orientée Minecraft (démarrer un service, envoyer une commande, voir les
joueurs, éditer un template) avec des comptes et des droits indépendants du RBAC du cluster.

## What Changes

- API REST `/api/v1` documentée par OpenAPI : tasks, groupes, templates (fichiers), services, logiciels, modules, joueurs, événements.
- Façade sur les CRDs : toute écriture REST modifie les ressources Kubernetes (cohérence avec kubectl).
- Comptes utilisateurs, rôles, permissions fines (extensibles par modules), jetons d'API, connexion par mot de passe.
- Console temps réel des services par WebSocket (logs + commandes) et flux d'événements en temps réel.
- Audit des actions d'écriture.

**Hors périmètre** : SSO/OIDC (laissé ouvert : fournisseur d'authentification par SPI `AuthProvider`).

## Capabilities

### New Capabilities
- `rest-api` : API HTTP de gestion du cloud.
- `access-control` : comptes, authentification, rôles et permissions.
- `service-console` : console interactive et logs des services.

### Modified Capabilities
- (aucune)

## Impact

Routes Javalin dans `ksmc-controller`, stockage des comptes (Secret K8s), génération OpenAPI, WebSocket.
