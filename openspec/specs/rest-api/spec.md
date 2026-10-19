# rest-api Specification

## Purpose
Offrir une API HTTP stable et documentée pour gérer tout le cloud, utilisée par la CLI, le dashboard,
les modules et les outils tiers, cohérente avec les ressources Kubernetes.

## Requirements

### Requirement: Ressources exposées
L'API SHALL offrir lecture, création, modification et suppression pour tasks, groupes, templates, services, logiciels et
modules, ainsi que la lecture des joueurs et des tasks agrégées.

#### Scenario: Création de task par REST
- **WHEN** un client autorisé fait `POST /api/v1/tasks` avec une task valide
- **THEN** la réponse est 201 et `kubectl get mctask` affiche la nouvelle task

### Requirement: Cohérence avec Kubernetes
Toute modification REST SHALL être appliquée à la ressource Kubernetes correspondante, et toute modification faite par kubectl SHALL être visible immédiatement par l'API.

#### Scenario: Modification kubectl
- **WHEN** l'utilisateur modifie `minServiceCount` avec kubectl
- **THEN** `GET /api/v1/tasks/lobby` renvoie la nouvelle valeur

### Requirement: Actions métier
L'API SHALL fournir : démarrer N services d'une task, arrêter/redémarrer un service, envoyer une commande à un service,
déployer un service vers un template, envoyer/expulser/notifier un joueur, et mettre une task en maintenance.

#### Scenario: Démarrage manuel
- **WHEN** un client fait `POST /api/v1/tasks/game/start` avec `{"count": 2}`
- **THEN** deux services supplémentaires sont créés et leurs noms sont retournés

### Requirement: Fichiers de templates
L'API SHALL permettre de lister, lire, écrire, renommer et supprimer des fichiers d'un template `storage`, et de téléverser/télécharger une archive.

#### Scenario: Édition d'un fichier
- **WHEN** un client fait `PUT /api/v1/templates/lobby/default/files/server.properties`
- **THEN** le fichier est remplacé et les prochains services l'utilisent

### Requirement: Contrat d'API
L'API SHALL publier sa description OpenAPI 3 sur `/api/v1/openapi.json`, utiliser des erreurs JSON uniformes (`code`, `message`, `details`),
la pagination par curseur pour les listes, et la concurrence optimiste (`resourceVersion`/ETag).

#### Scenario: Conflit d'écriture
- **WHEN** deux clients modifient la même task avec le même ETag
- **THEN** le second reçoit 409 avec le code `Conflict`

### Requirement: Flux d'événements
L'API SHALL proposer un flux temps réel des événements cloud (WebSocket et Server-Sent Events) filtrable comme les abonnements de messagerie.

#### Scenario: Dashboard à l'écoute
- **WHEN** un client est abonné à `service.*` et qu'un service démarre
- **THEN** il reçoit l'événement en moins de 2 s
