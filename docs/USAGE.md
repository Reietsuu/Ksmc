# Utiliser Ksmc

Toutes les ressources se gèrent avec `kubectl`, l'API REST ([API.md](API.md)), la CLI `ksmc` ([CLI.md](CLI.md)) et le
dashboard web ([DASHBOARD.md](DASHBOARD.md)).
Les exemples complets sont dans [`examples/`](../examples).

> ⚖️ **EULA Minecraft** : les serveurs ne démarrent que si l'opérateur accepte l'EULA
> (https://aka.ms/MinecraftEULA) à l'installation : `helm install … --set minecraft.acceptEula=true`.

## Tasks (`ServiceTask`, `mctask`)

Une task décrit une famille de services gérés ensemble, comme dans CloudNet. Ksmc maintient le bon nombre
de `CloudService`, nommés `<task>-<n>` (le plus petit numéro libre est réutilisé).

```yaml
apiVersion: ksmc.io/v1alpha1
kind: ServiceTask
metadata: {name: lobby}
spec:
  environment: server            # server | proxy
  software: {name: paper, version: latest}
  memoryMb: 1024
  minServiceCount: 2             # toujours au moins 2 services
  maxServiceCount: 5             # -1 = illimité
  groups: [plugins]              # groupes appliqués (en plus des groupes d'environnement)
  templates: [{name: lobby-default}]
  updateStrategy: {type: RollingUpdate, maxUnavailable: 1}
```

```bash
kubectl get mctask                          # ENV, SOFTWARE, VERSION, MIN, READY (prêts/total), PLAYERS
kubectl scale mctask lobby --replicas=3     # change minServiceCount
kubectl annotate mctask game ksmc.io/start-requests=2   # démarrer 2 services en plus (dans la limite de max)
kubectl patch mctask lobby --type merge -p '{"spec":{"maintenance":true}}'
kubectl patch mctask lobby --type merge -p '{"spec":{"enabled":false}}'   # arrête tous les services
kubectl describe mctask lobby               # événements : ServiceCreated, ServiceUpdated, Degraded...
```

Comportements :

- **Réduction** : les services arrêtés en premier sont ceux qui ne sont pas encore `Running`, puis ceux qui ont
  le moins de joueurs, puis le numéro le plus élevé.
- **Échecs** : un service dynamique `Failed` est supprimé puis remplacé. Après 5 échecs en moins de 10 minutes,
  la task passe en condition `Degraded=True` (avec la dernière raison) et les créations sont espacées
  (backoff jusqu'à 5 minutes). Un service qui atteint `Running` remet le compteur à zéro.
- **Arrêter un service précis** : `desiredState: Stopped` sur le `CloudService` ; un service dynamique est
  ensuite remplacé si le minimum l'exige.
- **Démarrages manuels** : `spec.extraServices` (ou l'annotation `ksmc.io/start-requests`) ajoute des services
  au-delà du minimum ; une demande qui dépasserait `maxServiceCount` est plafonnée (événement
  `MaxServiceCountReached`). Pour les retirer, diminuer `extraServices`.

### Services statiques

Avec `static: true`, chaque service garde son nom et un volume persistant `<service>-data` (taille
`storage.size`, défaut 2Gi). Un service statique qui s'arrête est redémarré sous le même nom ; réduire
le minimum l'**arrête** sans supprimer ni le service ni son volume, et l'augmenter le redémarre avec ses données.

### Mises à jour de configuration

Chaque service porte l'empreinte de la configuration effective (`ksmc.io/config-hash`). Quand la task ou
un de ses groupes change :

| `updateStrategy.type` | Effet |
|---|---|
| `RollingUpdate` (défaut) | remplace les services un par un (au plus `maxUnavailable` indisponibles) |
| `Recreate` | remplace tous les services obsolètes immédiatement |
| `OnRestart` | ne touche pas aux services existants ; les nouveaux utilisent la nouvelle configuration |

Si la mise à jour n'est pas terminée après `progressDeadlineSeconds` (défaut 600), la condition
`Progressing` passe à `False` (`ProgressDeadlineExceeded`).

### Autoscaling

Par défaut une task garde `minServiceCount` services (+ démarrages manuels). `spec.autoscaling` ajoute des
stratégies, combinées : la cible est la plus grande de leurs propositions, bornée par `minServiceCount` et
`maxServiceCount`. Exemple complet : la task `bedwars` de [`examples/04-tasks.yaml`](../examples/04-tasks.yaml).

| Champ | Défaut | Rôle |
|---|---|---|
| `strategies` | `[static]` | `players`, `schedule` ou une stratégie fournie par un module |
| `scaleUpThreshold` | 0.8 | `players` : un service de plus quand le remplissage moyen des services `Running` le **dépasse** |
| `idleStopSeconds` | 300 | `players` : un service vide depuis ce délai est arrêté (jamais sous le minimum) |
| `cooldownSeconds` | 60 | après une décision, pas de décision inverse avant ce délai |
| `maxConcurrentStarts` | 1 | services en démarrage simultanés au-delà du minimum |
| `preparedServices` | 0 | services démarrés d'avance, exclus du routage, promus quand il faut un service |
| `schedule` | — | règles `{from, to, timeZone, minServiceCount}` (cron Unix) : minimum entre `from` et `to` |

- **Remplissage** : joueurs / places des services routables `Running` (places annoncées par le bridge). Un service
  qui démarre ne compte pas encore : avec `maxConcurrentStarts: 1`, un seul démarrage à la fois.
- **Services vides** : l'instant depuis lequel chaque service est vide est mémorisé dans
  `status.autoscaling.emptySince` (il survit au redémarrage du controller) ; on arrête d'abord celui qui est vide
  depuis le plus longtemps.
- **Minimum programmé** (`schedule`) : appliqué immédiatement, sans limite de démarrages. Une règle est active
  entre une occurrence de `from` et l'occurrence suivante de `to` (les plages de nuit `0 22 * * *` → `0 6 * * *`
  fonctionnent). Après la plage, les services en trop s'arrêtent quand ils sont vides (si `players` est listé),
  sinon tout de suite.
- **Services préparés** : label `ksmc.io/prepared=true`, ignorés par les proxies. Quand il faut un service, un
  préparé (`Running` de préférence) est promu immédiatement (label retiré, événement `ServicePromoted`) et un
  nouveau préparé est démarré.
- **Transparence** : chaque décision produit un événement Kubernetes `ScaledUp`/`ScaledDown` sur la task
  (`kubectl describe mctask bedwars` → `2 → 3 : remplissage 85 % > seuil 80 %`) et un événement cloud
  `task.scaled` (`from`, `to`, `direction`, `reason`, `fill`). L'état courant est dans `status.autoscaling`
  (`desiredServices`, `observedFill`, `lastDecision`, `lastReason`, `lastScaleTime`).
- Les tasks avec autoscaling sont réévaluées toutes les 10 s (en plus des changements de joueurs).

## Groupes (`ServiceGroup`, `mcgroup`)

Un groupe partage une configuration (templates, options JVM, variables, propriétés, `podTemplate`) entre tasks.

```yaml
apiVersion: ksmc.io/v1alpha1
kind: ServiceGroup
metadata: {name: all-servers}
spec:
  targetEnvironments: [server]   # appliqué automatiquement à toutes les tasks "server"
  templates: [{name: global-plugins}]
  jvmOptions: ["-XX:+UseG1GC"]
  env: {TZ: Europe/Paris}
```

Ordre de fusion : groupes d'environnement (par nom) → groupes de `spec.groups` (dans l'ordre) → task.
Listes (templates, options JVM, arguments) concaténées ; maps (env, propriétés) : la valeur la plus spécifique
gagne ; `podTemplate` fusionné. La configuration retenue est visible dans `status.effective` de la task.
Une task qui référence un groupe inexistant ne crée aucun service (condition `Ready=False`, `GroupNotFound`).

## Réseau : proxies, fallback et bridges

### Exposer le réseau

Chaque task `proxy` reçoit un Service Kubernetes **du même nom** qui répartit les joueurs sur ses proxies prêts :

```yaml
apiVersion: ksmc.io/v1alpha1
kind: ServiceTask
metadata: {name: proxy}
spec:
  environment: proxy
  software: {name: velocity}
  minServiceCount: 2
  expose:
    type: LoadBalancer              # LoadBalancer (défaut) | NodePort | ClusterIP
    port: 25565
    # nodePort: 30565               # avec type NodePort
    maxPlayers: 1000                # affiché au ping (joueurs de tout le réseau / maximum)
    proxyProtocol: false            # true derrière un load balancer qui envoie le PROXY protocol
    externalTrafficPolicy: Local    # conserver l'IP source
    annotations: {}                 # ex. annotations de load balancer du fournisseur cloud
    loadBalancerSourceRanges: []
  forcedHosts:
    bedwars.example.com: bedwars    # se connecter par cet hôte envoie sur la task bedwars
  properties:
    ksmc.motd: "&6Mon réseau"
    ksmc.maintenance.whitelist: "Steve,Alex"     # joueurs admis pendant la maintenance
    ksmc.maintenance.message: "&cMaintenance en cours"
    ksmc.no-fallback.message: "Aucun lobby disponible"
    # ksmc.online-mode: "false"                 # réseau hors ligne (tests/LAN uniquement)
```

Les serveurs de jeu ne sont jamais exposés. Avec un CNI qui applique les NetworkPolicy,
`--set networkPolicy.enabled=true` interdit l'accès à leur port Minecraft à tout autre Pod que les proxies.

### Lobbies et fallback

Une task serveur marquée `fallback` reçoit les joueurs à la connexion et après une expulsion :

```yaml
spec:
  fallback: {enabled: true, priority: 10, permission: ""}   # permission optionnelle (ex. lobby.vip)
```

Le proxy choisit, parmi les services `Running` des tasks fallback accessibles au joueur, ceux de la plus haute
priorité, puis le **moins rempli**. Sans fallback disponible, le joueur est déconnecté avec `ksmc.no-fallback.message`.
Pendant la maintenance (`maintenance: true` sur la task proxy), seuls les joueurs de la liste blanche ou ayant la
permission `ksmc.maintenance.bypass` peuvent entrer.

### Plusieurs proxies

Une task proxy peut avoir plusieurs services (`minServices: 2`, autoscaling) et un namespace peut avoir plusieurs
tasks proxy. Les proxies partagent les serveurs et le registre des joueurs ; les réglages ci-dessous sont des
`properties` de la task proxy.

**Session unique.** Un joueur ne peut être connecté qu'à un seul proxy du réseau, toutes tasks proxy confondues.

| Propriété | Défaut | Effet |
|---|---|---|
| `ksmc.duplicate-login` | `deny` | `deny` : la nouvelle connexion est refusée ; `replace` : l'ancienne session est expulsée |
| `ksmc.duplicate-login.message` | `Tu es déjà connecté au réseau.` | message de refus (`deny`) |
| `ksmc.duplicate-login.replaced-message` | `Tu t'es connecté depuis un autre endroit.` | message de l'ancienne session (`replace`) |

Le pseudo compte aussi, sans tenir compte de la casse (en mode hors ligne, `Steve` et `steve` ont des UUID
différents mais restent une seule session). Une session restée sur un proxy qui ne tourne plus est ignorée. Si le cloud est injoignable, la connexion est acceptée.

**Commandes réseau.** Elles agissent sur tout le réseau, quel que soit le proxy du joueur, et marchent aussi depuis
la console du proxy (`ksmc service cmd proxy-1 glist`).

| Commande | Permission | Effet |
|---|---|---|
| `/glist [task]` | `ksmc.command.glist` | nombre de joueurs du réseau et répartition par serveur |
| `/find <joueur>` | `ksmc.command.find` | proxy et serveur actuels du joueur |
| `/send <joueur> <service\|task>` | `ksmc.command.send` | envoie le joueur sur ce service, ou sur le moins rempli de la task |
| `/alert <message>` | `ksmc.command.alert` | message à tous les joueurs de tous les proxies (préfixe `ksmc.alert.prefix`) |

Elles remplacent les `/glist` et `/send` de Velocity (qui ne voient que le proxy local). Avec
`ksmc.commands.replace-builtin: "false"`, les commandes de Velocity restent en place et celles de Ksmc s'utilisent
sous la forme `/ksmc glist`, `/ksmc send …`.

**Arrêt sans coupure.** Quand un proxy s'arrête (réduction de la task, mise à jour, `ksmc service stop`, suppression
du Pod), ses joueurs sont envoyés vers un autre proxy `Running` de la même task avant la fermeture :

- client **1.20.5 ou plus récent** : transfert automatique (paquet Transfer) vers l'adresse qu'il avait utilisée
  pour se connecter, le joueur n'a rien à faire ;
- client plus ancien, ou dernier proxy de la task : déconnexion avec `ksmc.transfer.fallback-message`.

| Propriété | Défaut | Effet |
|---|---|---|
| `ksmc.transfer.enabled` | `true` | `false` : pas de transfert, tout le monde reçoit le message de repli |
| `ksmc.transfer.address` | adresse utilisée par le joueur | `hôte[:port]` public du réseau (ex. derrière un DNS différent) |
| `ksmc.transfer.delay-ms` | `1500` | attente avant transfert, le temps que le Pod quitte le Service |
| `ksmc.transfer.fallback-message` | `Le proxy redémarre, reconnecte-toi.` | message des joueurs non transférés |

Ksmc active `accepts-transfers` dans `velocity.toml` pour que les proxies acceptent les joueurs transférés.

**Proxy figé (chien de garde).** Le bridge vérifie, depuis son propre thread, que le planificateur et le bus
d'événements de Velocity répondent. Sans réponse pendant `ksmc.watchdog.timeout-seconds`, le proxy est déclaré
figé : il sort du Service (sonde `/ready` en échec), transfère ses joueurs comme lors d'un arrêt, écrit l'état de
ses threads dans ses logs puis s'arrête (code 70) et la task le remplace. Événement `service.frozen` et métrique
`ksmc_service_frozen_total`.

| Propriété | Défaut | Effet |
|---|---|---|
| `ksmc.watchdog.enabled` | `true` | `false` : pas de chien de garde |
| `ksmc.watchdog.timeout-seconds` | `30` | silence toléré avant de déclarer le proxy figé (minimum 5) |
| `ksmc.watchdog.drain-timeout-seconds` | `10` | durée maximale du transfert des joueurs avant l'arrêt |
| `ksmc.watchdog.test` | `false` | active la commande console `ksmc freeze-test` (tests uniquement) |

Limite : un **crash net** (JVM tuée, mémoire épuisée) coupe forcément les joueurs, leur connexion se termine dans
le process. Ils se reconnectent par le Service, qui les envoie sur un proxy sain ; leur ancienne session ne les
bloque pas.

**Plusieurs tasks proxy.** Chaque task proxy a son propre Service (exposition), ses forced hosts, sa maintenance
et son maximum de joueurs ; toutes partagent les serveurs et le registre des joueurs, et la session unique vaut
pour tout le réseau. Le ping affiche les joueurs de tout le réseau, ou seulement ceux de la task avec
`ksmc.player-count.scope: task`. Le transfert à l'arrêt reste à l'intérieur d'une task.
Exemple : [`examples/07-multiproxy.yaml`](../examples/07-multiproxy.yaml).

### Bridges

Ksmc ajoute automatiquement un bridge à chaque service (`spec.bridge.enabled: false` pour le désactiver) :

| Logiciel | Bridge | Versions | Rôle |
|---|---|---|---|
| Velocity | plugin | API 4.x (Velocity 4) | enregistrement dynamique, fallback, forced hosts, maintenance, ping global, joueurs |
| BungeeCord / Waterfall | plugin | BungeeCord 1.8+ | idem |
| Paper / Spigot / Folia | plugin | 1.8.8 → dernière | état (joueurs, TPS, motd, état personnalisé), actions joueurs |
| Fabric | mod (+ Fabric API ajoutée) | 26.x | état, actions joueurs |
| NeoForge | mod | 26.x | état, actions joueurs |
| Forge (legacy) | — | — | pas de bridge : état par Server List Ping |

Sans bridge, un service fonctionne normalement : son état vient du ping du wrapper.

BungeeCord exige au moins un serveur dans `config.yml` : la configuration générée déclare un serveur factice
`ksmc-placeholder` (injoignable, restreint) comme serveur par défaut ; le bridge redirige chaque connexion
vers le serveur choisi (forced host ou fallback).

**Forwarding** : les serveurs sont configurés automatiquement selon les proxies du namespace — `modern`
(Velocity : Paper 1.13+ via `paper-global.yml`/`paper.yml`, Fabric via FabricProxy-Lite ajouté automatiquement),
sinon `legacy` (BungeeCord : `spigot.yml` `bungeecord: true`). Pour Forge/NeoForge, choisir le mode avec
`bridge.forwarding` selon le mod de forwarding installé. Un serveur Spigot (non Paper) derrière Velocity doit
utiliser `bridge.forwarding: legacy` côté proxy.

### Joueurs

Les proxies tiennent le registre des joueurs connectés (bucket `players`) et publient `player.login`,
`player.server-switch` et `player.disconnect`. Un joueur d'un proxy disparu sort du registre en moins de 30 s.

## Logiciels (`SoftwareDefinition`, `mcsw`)

Le chart installe neuf définitions : `paper`, `folia`, `spigot`, `fabric`, `forge`, `neoforge`
(serveurs) et `velocity`, `waterfall`, `bungeecord` (proxies).

```bash
kubectl get mcsw            # colonne LATEST : dernière version stable connue
kubectl get mcsw paper -o jsonpath='{.status.versions}'
```

| Résolveur | Logiciels | Versions acceptées |
|---|---|---|
| `papermc` | Paper, Folia, Velocity, Waterfall | `latest`, `1.21.4`, `1.21.4@232` (build) |
| `buildtools` | Spigot / CraftBukkit (construit une fois par un Job) | `latest`, `1.8.8`… |
| `fabric-meta` | Fabric | `latest`, `1.21.4`, `1.21.4@0.16.10` (loader) |
| `forge-installer` | Forge (installé une fois par un Job) | `1.20.1` (recommended), `1.20.1@47.3.0` |
| `neoforge-installer` | NeoForge 1.20.2+ | `1.21.1`, `1.21.1@21.1.262` |
| `jenkins` | BungeeCord | `latest`, numéro de build |
| `url` | n'importe quel JAR | modèle `https://…/{version}.jar` |

La version de Java est choisie automatiquement (8 jusqu'à 1.16.5, 17 jusqu'à 1.20.4, 21 jusqu'à 1.21.x,
25 pour 26.x) ou imposée par la source (PaperMC) ; l'image runtime correspondante est
`ghcr.io/reietsuu/ksmc-runtime:<version>-java<N>`. Pour imposer une image : `software.image`.

Logiciel personnalisé :

```yaml
apiVersion: ksmc.io/v1alpha1
kind: SoftwareDefinition
metadata: {name: purpur}
spec:
  environment: server
  family: paper            # détermine la configuration réseau appliquée
  resolver:
    type: url
    params:
      url: https://api.purpurmc.org/v2/purpur/{version}/latest/download
      versions: "1.21.4"
  javaVersions: [{minecraft: "1.20.5+", java: 21}]
```

## Templates (`ServiceTemplate`, `mctpl`)

Un template est une source de fichiers copiés dans le dossier du service avant le démarrage.
Une seule source par template :

```yaml
spec: {storage: {path: lobby/default}}                  # Template Store du controller
spec: {git: {url: https://github.com/moi/tpl.git, ref: main, path: lobby, secretRef: git-cred}}
spec: {http: {url: https://exemple.org/tpl.tar.gz, sha256: "…"}}   # zip ou tar.gz
spec: {configMap: {name: lobby-config}}                 # clé "plugins__Hello__config.yml" → plugins/Hello/config.yml
spec: {image: {image: ghcr.io/moi/tpl:1, path: /template}}         # dossier copié depuis une image
```

Ordre d'application : templates des groupes d'environnement → groupes référencés → task → service ;
un fichier appliqué plus tard remplace le précédent. Un template marqué `optional: true` absent
n'empêche pas le démarrage.

Pour un service **statique**, les templates ne sont appliqués qu'au premier démarrage : les données
des joueurs ne sont jamais écrasées.

Sauvegarder un service vers un template à l'arrêt :

```yaml
spec:
  deployOnStop:
    template: build/world
    excludes: ["logs/", "cache/", "*.log"]
```

## Services (`CloudService`, `mcsvc`)

Un `CloudService` est une instance. Il est normalement créé par une task, mais peut l'être seul :

```yaml
apiVersion: ksmc.io/v1alpha1
kind: CloudService
metadata: {name: sandbox}
spec:
  environment: server
  software: {name: paper, version: latest}
  memoryMb: 1024
  restartPolicy: OnFailure      # Never | OnFailure | Always
```

```bash
kubectl get mcsvc                       # TASK, PHASE, PLAYERS, AGE
kubectl get mcsvc -o wide               # + STATE, ADDRESS
kubectl describe mcsvc sandbox          # événements : PodCreated, PhaseRunning, ...
kubectl logs sandbox -c server -f       # console du serveur
kubectl attach -it sandbox -c server    # taper des commandes (ex. "list"), Ctrl+P Ctrl+Q pour quitter
```

Phases : `Pending` → `Preparing` (téléchargement, construction, templates) → `Starting` → `Running`
(le serveur répond au ping) → `Stopping` → `Stopped`, ou `Failed` avec une raison :

| Raison | Signification |
|---|---|
| `SoftwareNotFound` | `SoftwareDefinition` absente |
| `SoftwareResolutionFailed` | version inexistante |
| `SoftwareDownloadFailed` | téléchargement / construction en échec |
| `TemplateUnavailable` | template requis introuvable ou illisible |
| `ServerCrashed` / `OOMKilled` | le serveur s'est arrêté en erreur / manque de mémoire |
| `Unschedulable` (en `Pending`) | aucun nœud n'a les ressources demandées |

Arrêter sans supprimer : `kubectl patch mcsvc sandbox --type merge -p '{"spec":{"desiredState":"Stopped"}}'`.
Un service statique (`static: true`) conserve son volume (`<nom>-data`) même après suppression.

### Personnaliser le Pod

`podTemplate` accepte n'importe quel fragment de Pod Kubernetes, fusionné avec celui généré par Ksmc
(conteneurs et volumes fusionnés par nom) :

```yaml
spec:
  podTemplate:
    metadata:
      annotations: {prometheus.io/scrape: "true"}
    spec:
      nodeSelector: {pool: games}
      tolerations: [{key: games, operator: Exists}]
      priorityClassName: high
      containers:
        - name: server
          resources: {limits: {cpu: "2"}}
        - name: sidecar
          image: busybox
          command: ["sleep", "infinity"]
```

Mémoire : `memoryMb` fixe le heap Java ; la limite du conteneur ajoute 30 % (au moins 512 Mio) pour la mémoire
hors heap de la JVM. Prévoir au moins 1024 Mo de heap pour un serveur moderne.
