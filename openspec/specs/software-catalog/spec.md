# software-catalog Specification

## Purpose
Décrire de façon déclarative les logiciels Minecraft (serveurs et proxies) et savoir obtenir
l'artefact exact à exécuter pour un couple logiciel + version.

## Requirements

### Requirement: Définition déclarative d'un logiciel
Une `SoftwareDefinition` SHALL indiquer : nom, environnement (`proxy` | `server`), type de résolveur,
paramètres du résolveur, versions Java compatibles par plage de versions Minecraft, et arguments de lancement.

#### Scenario: Logiciel personnalisé par URL
- **WHEN** l'utilisateur crée une `SoftwareDefinition` `mypaper` de résolveur `url` avec un modèle d'URL contenant `{version}`
- **THEN** un service utilisant `mypaper` version `1.21.4` télécharge l'URL avec `{version}` remplacé par `1.21.4`

### Requirement: Logiciels par défaut
L'installation SHALL fournir les définitions `paper`, `folia`, `spigot`, `velocity`, `bungeecord`, `waterfall`,
`fabric`, `forge` et `neoforge`, désactivables par une valeur du chart.

#### Scenario: Catalogue initial
- **WHEN** Ksmc vient d'être installé avec les valeurs par défaut
- **THEN** `kubectl get mcsw` liste les neuf définitions

### Requirement: Résolution de version
Le système SHALL accepter une version exacte, `latest` (dernière version stable) ou un numéro de build explicite,
et SHALL publier dans `status` de la définition les versions disponibles connues.

#### Scenario: Version latest
- **WHEN** une task demande `paper` en version `latest`
- **THEN** le service démarre avec la dernière version stable de Paper et la version effective est inscrite dans le `status` du service

#### Scenario: Version inexistante
- **WHEN** un service demande `paper` version `0.0.1`
- **THEN** le service passe en phase `Failed` avec la raison `SoftwareResolutionFailed`

### Requirement: Intégrité et cache des artefacts
Les artefacts téléchargés SHALL être vérifiés par checksum quand la source en fournit un, et SHALL être mis
en cache partagé pour qu'une même version ne soit téléchargée ou construite qu'une fois par cluster.

#### Scenario: Deuxième service
- **WHEN** un deuxième service démarre avec le même logiciel et la même version
- **THEN** il utilise l'artefact du cache sans nouveau téléchargement

### Requirement: Logiciels à construire
Pour Spigot/Bukkit, le système SHALL construire l'artefact via BuildTools une seule fois par version et le
stocker dans le cache ; les services en attente SHALL rester en phase `Preparing` pendant la construction.

#### Scenario: Premier service Spigot
- **WHEN** le premier service `spigot` `1.20.4` est créé
- **THEN** une construction est lancée, le service est `Preparing`, puis `Running` après la fin de la construction

### Requirement: Choix automatique de Java
Le système SHALL choisir l'image runtime Java compatible : version minimale annoncée par la source si elle en
fournit une, sinon règles par version Minecraft (8 jusqu'à 1.16.5, 17 de 1.17 à 1.20.4, 21 de 1.20.5 à 1.21.x,
25 à partir de 26.1), sauf si la task impose une image.

#### Scenario: Serveur legacy
- **WHEN** un service `paper` `1.8.8` est créé
- **THEN** son Pod utilise l'image `ksmc-runtime:java8`

#### Scenario: Version à numérotation annuelle
- **WHEN** un service `paper` `26.1.2` est créé
- **THEN** son Pod utilise l'image `ksmc-runtime:java25`
