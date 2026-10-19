# CLI `ksmc`

La CLI pilote le cloud depuis un terminal, de manière interactive ou dans des scripts. Elle utilise l'API REST
([API.md](API.md)) : mêmes comptes, mêmes permissions.

## Installation

- **Archive** : `ksmc-cli-<version>.zip` dans les [releases](https://github.com/Reietsuu/Ksmc/releases) ;
  décompresser et ajouter `ksmc-<version>/bin` au `PATH` (`bin/ksmc`, `bin/ksmc.bat` sous Windows). Java 21 requis.
- **Image** : `docker run --rm -it -v ~/.ksmc:/home/ksmc/.ksmc ghcr.io/reietsuu/ksmc-cli task list`.
- **Depuis les sources** : `./gradlew :ksmc-cli:installDist` puis `ksmc-cli/build/install/ksmc/bin/ksmc`.

## Connexion et contextes

```bash
kubectl -n ksmc port-forward svc/ksmc-controller 8080 &
ksmc login http://localhost:8080            # utilisateur admin, mot de passe demandé
ksmc login https://ksmc.example.com -u alice --name prod
ksmc login http://localhost:8080 --api-token ksmc_...   # jeton d'API (CI)
```

À la première connexion du compte `admin`, le nouveau mot de passe est demandé (ou `--new-password`).
Pour un script : `--password-stdin` (le mot de passe sur l'entrée standard).

Les contextes (URL + jetons) sont dans `~/.ksmc/config.yaml`, lisible par l'utilisateur seul (`$KSMC_CONFIG` pour
un autre fichier). La session est renouvelée automatiquement.

```bash
ksmc context list
ksmc context use test        # les commandes suivantes visent « test »
ksmc --context prod task list
ksmc --server http://localhost:8080 --token ksmc_... task list   # sans contexte ($KSMC_TOKEN accepté)
ksmc password                # changer son mot de passe
```

## Commandes

Chaque commande a son aide : `ksmc <commande> --help`.

| Commande | Sous-commandes |
|---|---|
| `task` | `list`, `get`, `create`, `apply -f`, `edit`, `delete`, `start -n`, `maintenance on/off` |
| `group` | `list`, `get`, `apply -f`, `edit`, `delete` |
| `template` | `list`, `get`, `apply -f`, `edit`, `delete` ; fichiers : `ls`, `cat`, `put`, `rm`, `push`, `pull` |
| `service` | `list [-t task]`, `get`, `stop`, `start`, `restart`, `cmd`, `console`, `logs [-f] [-p] [--last-failure]`, `deploy`, `apply -f`, `delete` |
| `player` | `list`, `get`, `send (--task / --service)`, `kick`, `msg`, `title` |
| `software` | `list`, `get`, `apply -f`, `edit`, `delete` |
| `module` | `list`, `get`, `install`, `config`, `enable`, `disable`, `remove`, `apply -f` |
| `user` | `list`, `create`, `delete`, `roles`, `passwd`, `role-list`, `role-set`, `me` |
| `token` | `list [--all]`, `create [--days]`, `revoke` |
| `events` | flux en direct : `-t service.*`, `--task`, `-o json` |
| `context`, `login`, `password`, `completion` | voir plus haut et plus bas |

Exemples :

```bash
ksmc task create lobby -s paper:1.21.4 --min 2 --max 10 -m 2048
ksmc task apply -f examples/04-tasks.yaml      # crée, ou remplace si la ressource existe
ksmc task edit lobby                           # $EDITOR, avec contrôle de concurrence
ksmc task start game -n 2                      # affiche les noms des services créés
ksmc task maintenance lobby on

ksmc service list -t lobby
ksmc service cmd lobby-1 say Redémarrage dans 5 minutes
ksmc service logs lobby-1 -n 500
ksmc service logs lobby-1 --last-failure       # extrait conservé du dernier crash
ksmc service deploy lobby-1 lobby/backup       # copie le dossier du service dans un template

ksmc template ls lobby/default
ksmc template put lobby/default server.properties ./server.properties
ksmc template push lobby/default ./mon-template     # dossier, .zip ou .tar.gz (--merge pour fusionner)
ksmc template pull lobby/default ./copie

ksmc player send Steve --task bedwars
ksmc module install hello --store modules/hello.jar -c greeting=Bonjour
ksmc module config hello greeting=Salut         # sans rechargement du module
ksmc user create alice -r operator              # mot de passe demandé, à changer à la première connexion
ksmc token create ci --days 90                  # le jeton n'est affiché qu'une fois
ksmc events -t 'service.*'
```

## Console

```bash
ksmc service console lobby-1
```

Affiche les 200 dernières lignes (`-n`) puis le direct ; chaque ligne tapée est envoyée comme commande
(`ksmc.service.command` requise, sinon lecture seule). Ctrl+D pour quitter. `ksmc service logs lobby-1 -f` suit
les logs sans saisie.

## Scripts

- Sorties : `-o table` (défaut), `json`, `yaml`, `name` (un nom par ligne, sans en-tête) ; `get` affiche le YAML.
- Codes de sortie : `0` succès, `1` erreur d'usage (option, argument, contexte), `2` erreur de l'API ou du réseau
  (message sur stderr, ex. `erreur : Forbidden : permission manquante : ksmc.service.write`).

```bash
for s in $(ksmc service list -t lobby -o name); do
  ksmc service cmd "$s" say "Maintenance dans 10 minutes"
done
ksmc service list -o json | jq '.[] | select(.status.phase == "Running") | .metadata.name'
```

## Commandes des modules

Les commandes déclarées par les modules du controller (`ModuleContext.cliCommand`, voir [MODULES.md](MODULES.md))
apparaissent sous `ksmc <module> <commande>` sans mise à jour de la CLI :

```bash
ksmc hello --help
ksmc hello broadcast "Bonjour à tous"
```

La liste est lue sur `GET /api/v1/cli/commands` et gardée 10 minutes en cache (`~/.ksmc/cache/`). Un module ne
peut pas remplacer une commande intégrée.

## Complétion

```bash
source <(ksmc completion bash)                                   # bash (~/.bashrc)
autoload -U bashcompinit && bashcompinit && source <(ksmc completion zsh)   # zsh
ksmc completion fish | source                                    # fish
```
