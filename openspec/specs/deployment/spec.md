# deployment Specification

## Purpose
Permettre d'installer, configurer, mettre à jour et retirer Ksmc sur n'importe quel cluster
Kubernetes conforme, avec un seul chart Helm.

## Requirements

### Requirement: Installation par Helm
Ksmc SHALL s'installer avec `helm install ksmc deploy/helm/ksmc -n <namespace>` et installer les CRDs,
le RBAC minimal, le controller et un serveur NATS.

#### Scenario: Installation sur cluster vide
- **WHEN** l'opérateur installe le chart sur un cluster kind vide
- **THEN** en moins de 3 minutes le Pod du controller et le Pod NATS sont `Ready`

### Requirement: Santé du controller
Le controller SHALL exposer `/healthz` (process vivant) et `/readyz` (connecté à l'API Kubernetes et à NATS).

#### Scenario: Readiness
- **WHEN** NATS est indisponible
- **THEN** `/readyz` répond 503 et `/healthz` répond 200

### Requirement: Configuration par values
Le chart SHALL permettre de configurer : images et tags, ressources du controller, classe de stockage
et taille du template store, activation de NATS embarqué ou URL NATS externe, type de Service des proxies,
et l'installation des définitions de logiciels par défaut.

#### Scenario: NATS externe
- **WHEN** l'opérateur installe avec `nats.enabled=false` et `nats.url=nats://ext:4222`
- **THEN** aucun Pod NATS n'est créé et le controller se connecte à l'URL fournie

### Requirement: Désinstallation sûre
La désinstallation SHALL supprimer controller et NATS sans supprimer les CRDs ni les volumes des services statiques.

#### Scenario: helm uninstall
- **WHEN** l'opérateur exécute `helm uninstall ksmc`
- **THEN** les CRDs et les PersistentVolumeClaims des services statiques existent toujours
