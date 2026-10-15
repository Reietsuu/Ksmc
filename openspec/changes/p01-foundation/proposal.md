# Proposal

## Why

Ksmc démarre de zéro. Avant toute logique d'orchestration, il faut un socle commun : un build
multi-projets, le modèle déclaratif (CRDs) que kubectl, REST, CLI et dashboard partageront, un
chart Helm installable et une CI qui vérifie chaque push. Sans ce socle, les changes suivants ne
peuvent ni être testés ni être livrés.

## What Changes

- Dépôt GitHub public `Reietsuu/Ksmc`, build Gradle multi-projets (Java 21 + projets en bytecode Java 8).
- Définition des six CRDs `ksmc.io/v1alpha1` : `ServiceTask`, `ServiceGroup`, `ServiceTemplate`,
  `CloudService`, `SoftwareDefinition`, `KsmcModule`, avec validation OpenAPI, colonnes
  d'affichage, noms courts et sous-ressources `status` (et `scale` pour `ServiceTask`).
- Un controller minimal qui démarre, s'enregistre auprès de l'API Kubernetes et expose `/healthz` et `/readyz`.
- Chart Helm `deploy/helm/ksmc` qui installe CRDs, RBAC, controller et NATS.
- Pipeline GitHub Actions : build + tests unitaires sur chaque push/PR, images publiées sur GHCR
  depuis `main`, job e2e sur cluster kind (squelette, enrichi par les changes suivants).

**Hors périmètre** : toute logique de réconciliation (p02+), REST (p08), dashboard (p10).
**Laissé ouvert** : la version `v1alpha1` sera promue plus tard via conversion webhook ; le
chart accepte des valeurs supplémentaires pour les futurs composants.

## Capabilities

### New Capabilities
- `resource-model` : les types de ressources déclaratives Ksmc, leurs champs communs, leur validation et leur affichage kubectl.
- `deployment` : installation, mise à jour et désinstallation de Ksmc sur un cluster.
- `delivery-pipeline` : vérification automatique du code et publication des artefacts.

### Modified Capabilities
- (aucune)

## Impact

Création de tout le dépôt : `build.gradle.kts`, `settings.gradle.kts`, `ksmc-api`, `ksmc-controller`,
`deploy/helm/ksmc`, `.github/workflows`, `scripts/e2e-kind.sh`. Dépendances : fabric8
kubernetes-client, Java Operator SDK, JUnit 5, NATS (chart officiel).
