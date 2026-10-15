# service-runtime Specification

## Purpose
Définir le comportement du conteneur d'un service Ksmc : préparation des fichiers, configuration
réseau, lancement et supervision du serveur Minecraft, santé et arrêt propre.

## Requirements

### Requirement: Préparation du service
Avant le lancement, le runtime SHALL : récupérer l'artefact logiciel, appliquer les templates, puis écrire la
configuration réseau (port 25565, `online-mode=false` et forwarding moderne Velocity ou BungeeCord pour les
serveurs derrière un proxy).

#### Scenario: Paper derrière Velocity
- **WHEN** un service Paper démarre dans un namespace où le proxy est Velocity
- **THEN** sa configuration active le forwarding Velocity avec le secret partagé du namespace et désactive `online-mode`

### Requirement: Supervision du process
Le runtime SHALL lancer le serveur avec les options JVM et la mémoire configurées, relayer stdout/stderr
dans les logs du conteneur et se terminer avec le code de sortie du serveur.

#### Scenario: Crash du serveur
- **WHEN** le process Minecraft se termine avec un code non nul
- **THEN** le conteneur se termine avec ce même code

### Requirement: Santé
Le runtime SHALL exposer sur le port 8090 : `/live` (le process tourne) et `/ready` (le serveur répond à un
Server List Ping), sans nécessiter de plugin dans le serveur.

#### Scenario: Serveur en démarrage
- **WHEN** le monde est encore en génération
- **THEN** `/live` répond 200 et `/ready` répond 503

### Requirement: Commandes console
Le runtime SHALL accepter des commandes console (via messagerie, p04, et via stdin du conteneur) et les
transmettre au serveur.

#### Scenario: Commande par stdin
- **WHEN** l'opérateur exécute `kubectl attach -it <pod>` puis tape `say hello`
- **THEN** le serveur exécute la commande

### Requirement: Arrêt gracieux
À la réception de SIGTERM, le runtime SHALL envoyer la commande d'arrêt propre au logiciel (`stop` ou `end`),
attendre jusqu'à `stopTimeoutSeconds` (défaut 30), puis tuer le process.

#### Scenario: Suppression du Pod
- **WHEN** le Pod d'un service Paper est supprimé
- **THEN** le monde est sauvegardé par la commande `stop` avant la fin du conteneur
