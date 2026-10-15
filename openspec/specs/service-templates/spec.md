# service-templates Specification

## Purpose
Fournir aux services leurs fichiers (configurations, plugins, mondes) depuis des sources variées,
dans un ordre prévisible, et permettre de sauvegarder l'état d'un service dans un template.

## Requirements

### Requirement: Sources de templates
Un `ServiceTemplate` SHALL avoir exactement une source parmi : `storage` (chemin dans le Template Store),
`git` (URL, ref, sous-dossier, secret d'accès), `http` (archive tar.gz/zip), `configMap` et `image` (image OCI dont un dossier est copié).

#### Scenario: Template git
- **WHEN** une task référence un template `git` pointant sur un dépôt contenant `plugins/Hello.jar`
- **THEN** le service démarré contient `plugins/Hello.jar`

### Requirement: Ordre d'application
Les templates SHALL être appliqués dans l'ordre : templates globaux de l'environnement, templates des groupes
(dans l'ordre de la task), templates de la task, puis templates propres au service ; un fichier appliqué plus
tard SHALL remplacer un fichier de même chemin.

#### Scenario: Surcharge
- **WHEN** un groupe et la task fournissent tous deux `server.properties`
- **THEN** le service utilise la version de la task

### Requirement: Template Store
Le controller SHALL fournir un stockage de templates persistant, organisé en `<préfixe>/<nom>`, lisible et
modifiable (lister, lire, écrire, supprimer, téléverser une archive, télécharger une archive).

#### Scenario: Création d'un template stocké
- **WHEN** l'utilisateur téléverse une archive dans `lobby/default`
- **THEN** les services qui utilisent ce template reçoivent ces fichiers à leur prochain démarrage

### Requirement: Sauvegarde vers un template
Un service SHALL pouvoir copier tout ou partie de ses fichiers vers un template `storage`, à la demande ou
automatiquement à l'arrêt si la task active `deployOnStop` ; des motifs d'exclusion SHALL être configurables.

#### Scenario: Déploiement à l'arrêt
- **WHEN** un service avec `deployOnStop` vers `build/world` s'arrête
- **THEN** ses fichiers (hors exclusions) sont écrits dans `build/world` avant la suppression du Pod

### Requirement: Échec de template
Une source indisponible SHALL faire passer le service en `Failed` avec la raison `TemplateUnavailable`
et le nom du template, sauf si le template est marqué `optional`.

#### Scenario: Template optionnel absent
- **WHEN** un template `optional: true` pointe vers un chemin inexistant
- **THEN** le service démarre normalement et un événement Kubernetes `Warning` est émis
