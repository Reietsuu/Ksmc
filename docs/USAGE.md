# Utiliser Ksmc

Toutes les ressources se gèrent avec `kubectl` (et bientôt l'API REST, la CLI `ksmc` et le dashboard).
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
