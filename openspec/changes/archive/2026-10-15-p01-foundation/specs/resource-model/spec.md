# Spec Delta

## Purpose

Définir les ressources Kubernetes déclaratives de Ksmc (tasks, groupes, templates, services,
logiciels, modules), source de vérité unique partagée par kubectl, l'API REST, la CLI et le dashboard.

## ADDED Requirements

### Requirement: Groupe d'API et types de ressources
Le système SHALL enregistrer le groupe d'API `ksmc.io` version `v1alpha1` avec les types namespacés
`ServiceTask`, `ServiceGroup`, `ServiceTemplate`, `CloudService`, `SoftwareDefinition` et `KsmcModule`.

#### Scenario: CRDs installées
- **WHEN** l'opérateur exécute `kubectl api-resources --api-group=ksmc.io` après installation
- **THEN** les six types sont listés comme namespacés avec les noms courts `mctask`, `mcgroup`, `mctpl`, `mcsvc`, `mcsw` et `mcmod`

### Requirement: ServiceTask décrit une famille de services
Une `ServiceTask` SHALL décrire : l'environnement (`proxy` ou `server`), la référence au logiciel
(`software.name` + `software.version`), les groupes et templates appliqués, la mémoire et les
ressources, `minServiceCount`, `maxServiceCount` (-1 = illimité), le caractère `static`, un `podTemplate` optionnel et un bloc `autoscaling` optionnel.

#### Scenario: Task valide acceptée
- **WHEN** un utilisateur applique une `ServiceTask` `lobby` avec environnement `server`, logiciel `paper` `1.21.4` et `minServiceCount: 2`
- **THEN** la ressource est créée et `kubectl get mctask` affiche `lobby` avec son environnement, son logiciel, le nombre minimum et le nombre de services prêts

#### Scenario: Task invalide refusée
- **WHEN** un utilisateur applique une `ServiceTask` avec `minServiceCount: -2` ou un environnement inconnu
- **THEN** l'API Kubernetes refuse la ressource avec un message de validation

### Requirement: Mise à l'échelle standard des tasks
`ServiceTask` SHALL exposer la sous-ressource `scale` liée à `spec.minServiceCount` et `status.readyServices`.

#### Scenario: kubectl scale
- **WHEN** l'utilisateur exécute `kubectl scale mctask lobby --replicas=3`
- **THEN** `spec.minServiceCount` de `lobby` vaut 3

### Requirement: CloudService représente une instance
Un `CloudService` SHALL représenter une instance unique d'un serveur ou proxy, référencer sa task
d'origine (optionnelle pour un service autonome) et exposer dans `status` : phase (`Pending`, `Preparing`, `Starting`, `Running`, `Stopping`, `Stopped`, `Failed`), adresse, joueurs en ligne, joueurs max, motd, horodatages et propriétés libres.

#### Scenario: Affichage des services
- **WHEN** l'utilisateur exécute `kubectl get mcsvc`
- **THEN** chaque service affiche sa task, sa phase, ses joueurs `en ligne/max` et son âge

### Requirement: Ressources de support
`ServiceGroup` SHALL porter une configuration partagée (templates, JVM, env, podTemplate) applicable
à plusieurs tasks ; `ServiceTemplate` SHALL décrire une source de fichiers ; `SoftwareDefinition` SHALL
décrire comment obtenir un logiciel serveur ; `KsmcModule` SHALL décrire un module à charger et sa configuration.

#### Scenario: Ressources de support créées
- **WHEN** l'utilisateur applique un exemple de chacune de ces ressources depuis `examples/`
- **THEN** chacune est acceptée par l'API Kubernetes et apparaît avec `kubectl get`

### Requirement: Conditions et événements standard
Chaque ressource Ksmc SHALL exposer `status.conditions` (format Kubernetes standard : `type`, `status`,
`reason`, `message`, `lastTransitionTime`) et `status.observedGeneration`.

#### Scenario: Ressource réconciliée
- **WHEN** le controller a traité la génération courante d'une ressource
- **THEN** `status.observedGeneration` est égal à `metadata.generation` et une condition `Ready` est présente

### Requirement: Champs extensibles
Chaque `spec` SHALL accepter une map `extensions` (clé = id de module, valeur = objet libre) conservée
sans validation, pour que les modules ajoutent des réglages sans modifier les CRDs.

#### Scenario: Extension conservée
- **WHEN** une `ServiceTask` contient `spec.extensions.mymodule.foo: bar`
- **THEN** la valeur est conservée telle quelle et relue identique via kubectl
