# cli Specification

## Purpose
Permettre de gérer tout le cloud depuis un terminal, de manière interactive ou scriptée,
avec les mêmes possibilités que l'API REST.

## Requirements

### Requirement: Connexion et contextes
La CLI SHALL permettre `ksmc login <url>` (identifiants ou jeton), stocker plusieurs contextes dans `~/.ksmc/config.yaml`
(fichier lisible par l'utilisateur seul) et basculer avec `ksmc context use <nom>`.

#### Scenario: Deux clusters
- **WHEN** l'utilisateur s'est connecté à `prod` et `test` puis exécute `ksmc context use test`
- **THEN** les commandes suivantes s'adressent à `test`

### Requirement: Couverture fonctionnelle
La CLI SHALL couvrir : `task` (list/get/create/apply -f/edit/delete/start/maintenance), `group`, `template` (list/ls/get/put/rm/push/pull),
`service` (list/get/stop/restart/cmd/console/logs/deploy), `player` (list/send/kick/msg), `module` (list/install/config/remove),
`user`, `token` et `events`.

#### Scenario: Démarrer des services
- **WHEN** l'utilisateur exécute `ksmc task start game -n 2`
- **THEN** deux services sont créés et leurs noms sont affichés

### Requirement: Console interactive
`ksmc service console <nom>` SHALL afficher les logs en direct et envoyer chaque ligne tapée comme commande, jusqu'à Ctrl+D.

#### Scenario: Console
- **WHEN** l'utilisateur tape `list` dans `ksmc service console lobby-1`
- **THEN** la réponse du serveur s'affiche

### Requirement: Sorties scriptables
Chaque commande de lecture SHALL accepter `-o table|json|yaml|name`, et la CLI SHALL sortir avec 0 en cas de succès, 1 en cas d'erreur
d'usage, 2 en cas d'erreur API (message sur stderr).

#### Scenario: Script
- **WHEN** un script exécute `ksmc service list -t lobby -o name`
- **THEN** il reçoit un nom de service par ligne, sans en-tête

### Requirement: Commandes de modules
La CLI SHALL découvrir les commandes déclarées par les modules et les exposer sous `ksmc <module-id> <commande>` sans mise à jour de la CLI.

#### Scenario: Module hello
- **WHEN** le module `hello` déclare la commande `greet <name>`
- **THEN** `ksmc hello greet Steve` appelle la route du module et affiche la réponse

### Requirement: Aide et complétion
La CLI SHALL fournir `--help` sur chaque commande et générer la complétion bash/zsh/fish.

#### Scenario: Complétion
- **WHEN** l'utilisateur exécute `ksmc completion bash`
- **THEN** un script de complétion valide est affiché
