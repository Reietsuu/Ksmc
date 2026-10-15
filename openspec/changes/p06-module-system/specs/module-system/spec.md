# Spec Delta

## Purpose

Permettre d'ajouter des fonctionnalités au cloud par des modules installables, configurables et
rechargeables, qui ont accès à tous les points d'extension de Ksmc.

## ADDED Requirements

### Requirement: Déclaration et installation d'un module
Un module SHALL être installé en créant une ressource `KsmcModule` indiquant sa source (URL HTTPS avec sha256,
coordonnées Maven, ou artefact du Template Store), sa version et sa configuration.

#### Scenario: Installation
- **WHEN** l'utilisateur applique le `KsmcModule` de l'exemple `hello` avec son URL et son sha256
- **THEN** le module est téléchargé, vérifié, chargé, et `kubectl get mcmod` affiche `hello` en phase `Enabled`

#### Scenario: Checksum invalide
- **WHEN** le sha256 ne correspond pas au fichier téléchargé
- **THEN** le module n'est pas chargé et sa phase est `Failed` raison `ChecksumMismatch`

### Requirement: Descripteur de module
Chaque module SHALL contenir `ksmc-module.json` : id, nom, version, classe principale, version d'API Ksmc requise, dépendances (id + plage de versions, optionnelles ou non).

#### Scenario: API incompatible
- **WHEN** un module requiert une version majeure d'API supérieure à celle du controller
- **THEN** il n'est pas chargé et sa raison est `IncompatibleApiVersion`

### Requirement: Cycle de vie et dépendances
Les modules SHALL être chargés dans l'ordre de leurs dépendances et passer par `Loaded` → `Enabled` → `Disabled` ;
une dépendance obligatoire manquante SHALL empêcher l'activation ; une erreur dans un module SHALL ne jamais arrêter le controller ni les autres modules.

#### Scenario: Exception au démarrage
- **WHEN** la méthode d'activation d'un module lève une exception
- **THEN** ce module passe `Failed` avec le message, et les autres modules et le controller continuent

### Requirement: Isolation
Chaque module SHALL avoir son propre classloader ne voyant que l'API publique Ksmc, ses propres classes et celles de ses dépendances déclarées.

#### Scenario: Bibliothèques en conflit
- **WHEN** deux modules embarquent deux versions différentes d'une même bibliothèque
- **THEN** chacun utilise sa propre version sans erreur

### Requirement: Configuration et rechargement
La configuration du module SHALL venir de `KsmcModule.spec.config` ; une modification SHALL être transmise au module
sans redémarrage du controller ; un changement de version ou de source SHALL recharger le module.

#### Scenario: Changement de configuration
- **WHEN** l'utilisateur modifie `spec.config.greeting` du module `hello`
- **THEN** le module reçoit la nouvelle configuration et l'utilise sans redémarrage du controller

### Requirement: Points d'extension
Un module SHALL pouvoir enregistrer : résolveurs de logiciels, sources de templates, personnalisations de Pods, stratégies
de scaling, transport de messagerie, écouteurs d'événements, routes REST sous `/api/v1/modules/<id>/`, commandes CLI
déclaratives, pages du dashboard et permissions ; tout ce qu'il enregistre SHALL être retiré à sa désactivation.

#### Scenario: Route REST de module
- **WHEN** le module `hello` enregistre `GET /hello`
- **THEN** `GET /api/v1/modules/hello/hello` répond, et répond 404 après désactivation du module

#### Scenario: Personnalisation de Pod
- **WHEN** un module ajoute un label à tous les Pods de services
- **THEN** les nouveaux services portent ce label

### Requirement: Modules intégrés
Le controller SHALL pouvoir embarquer des modules intégrés activables par la configuration du chart, chargés par le même mécanisme.

#### Scenario: Module intégré désactivé
- **WHEN** un module intégré est désactivé dans les values
- **THEN** il n'est pas chargé et n'apparaît pas comme `Enabled`
