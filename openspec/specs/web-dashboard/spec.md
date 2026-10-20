# web-dashboard Specification

## Purpose
Permettre de superviser et gérer tout le cloud depuis un navigateur, en temps réel, avec les mêmes
droits que l'API.

## Requirements

### Requirement: Accès et authentification
Le dashboard SHALL être servi par le controller sur `/`, exiger une connexion et masquer ou désactiver les actions non autorisées par les permissions de l'utilisateur.

#### Scenario: Viewer
- **WHEN** un utilisateur `viewer` ouvre la page d'un service
- **THEN** les boutons d'arrêt et la saisie de console ne sont pas utilisables

### Requirement: Vue d'ensemble temps réel
La page d'accueil SHALL afficher : nombre de joueurs total, services par phase, tasks et leur état, derniers événements, mis à jour sans rechargement.

#### Scenario: Démarrage d'un service
- **WHEN** un service démarre pendant que la vue d'ensemble est ouverte
- **THEN** les compteurs se mettent à jour en moins de 3 s

### Requirement: Gestion des tasks et groupes
Le dashboard SHALL permettre de créer, modifier (formulaire ou YAML, avec validation), démarrer des services, mettre en maintenance et supprimer des tasks et groupes.

#### Scenario: Création par formulaire
- **WHEN** l'utilisateur remplit le formulaire de task (nom, logiciel, version, mémoire, minimum) et valide
- **THEN** la task est créée et visible avec kubectl

### Requirement: Services et console
Le dashboard SHALL lister les services avec filtres, afficher leur détail et proposer une console intégrée (logs en direct + saisie de commandes).

#### Scenario: Console web
- **WHEN** l'utilisateur tape `list` dans la console web de `lobby-1`
- **THEN** la sortie apparaît dans la console

### Requirement: Éditeur de templates
Le dashboard SHALL proposer un explorateur de fichiers des templates `storage` avec édition des fichiers texte, téléversement et téléchargement.

#### Scenario: Édition de configuration
- **WHEN** l'utilisateur modifie `server.properties` dans l'éditeur et enregistre
- **THEN** le fichier du template est mis à jour

### Requirement: Joueurs, modules et administration
Le dashboard SHALL proposer la liste des joueurs (avec actions), la gestion des modules (installation, configuration, état), des utilisateurs, des jetons et la consultation de l'audit.

#### Scenario: Expulsion depuis le dashboard
- **WHEN** l'utilisateur expulse Steve depuis la liste des joueurs
- **THEN** Steve est déconnecté

### Requirement: Pages de modules
Les pages déclarées par les modules SHALL apparaître dans le menu et se charger sans reconstruire le dashboard.

#### Scenario: Module hello
- **WHEN** le module `hello` est activé
- **THEN** une entrée « Hello » apparaît dans le menu et affiche la page du module

### Requirement: Utilisabilité
Le dashboard SHALL être utilisable sur mobile (largeur 375 px), proposer un thème sombre, et avoir tous ses textes dans des fichiers de traduction (français par défaut).

#### Scenario: Mobile
- **WHEN** le dashboard est ouvert sur un écran de 375 px
- **THEN** la navigation et les tableaux restent utilisables sans défilement horizontal de la page
