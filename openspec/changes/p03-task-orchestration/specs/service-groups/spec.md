# Spec Delta

## Purpose

Partager une configuration commune (templates, JVM, variables, Pod) entre plusieurs tasks,
comme les groupes de configuration de CloudNet.

## ADDED Requirements

### Requirement: Groupes référencés par les tasks
Une task SHALL pouvoir référencer une liste ordonnée de `ServiceGroup` ; templates, options JVM, variables
d'environnement, propriétés et `podTemplate` des groupes SHALL s'appliquer à ses services.

#### Scenario: Plugin commun
- **WHEN** le groupe `global-plugins` contient un template avec un plugin et que les tasks `lobby` et `game` le référencent
- **THEN** les services des deux tasks contiennent le plugin

### Requirement: Groupes d'environnement
Un groupe SHALL pouvoir cibler tous les services d'un environnement (`targetEnvironments: [proxy]` ou `[server]`) sans être référencé explicitement.

#### Scenario: Groupe de tous les proxies
- **WHEN** un groupe cible `proxy`
- **THEN** il s'applique à toutes les tasks proxy du namespace, avant les groupes référencés

### Requirement: Règle de fusion
La fusion SHALL être : listes (templates, options JVM) concaténées dans l'ordre groupes d'environnement → groupes
référencés → task ; maps (env, propriétés) fusionnées avec priorité à la plus spécifique ; `podTemplate` en strategic merge dans le même ordre.

#### Scenario: Variable surchargée
- **WHEN** un groupe définit `ENV_A=1` et la task `ENV_A=2`
- **THEN** les services de la task ont `ENV_A=2`

### Requirement: Propagation des changements de groupe
Modifier un groupe SHALL déclencher la mise à jour de toutes les tasks concernées selon leur `updateStrategy`,
et une task référant un groupe inexistant SHALL avoir la condition `Ready=False` raison `GroupNotFound`.

#### Scenario: Groupe manquant
- **WHEN** une task référence le groupe `absent`
- **THEN** aucun service n'est créé et la condition indique `GroupNotFound`

### Requirement: Configuration effective visible
Le système SHALL exposer la configuration effective après fusion dans `ServiceTask.status.effective` (résumé : templates, groupes appliqués, hash de configuration).

#### Scenario: Inspection
- **WHEN** l'utilisateur exécute `kubectl get mctask lobby -o yaml`
- **THEN** `status.effective.groups` liste les groupes appliqués dans l'ordre
