# Utiliser Ksmc

Toutes les ressources se gèrent avec `kubectl` (et bientôt l'API REST, la CLI `ksmc` et le dashboard).
Les exemples complets sont dans [`examples/`](../examples).

> ⚖️ **EULA Minecraft** : les serveurs ne démarrent que si l'opérateur accepte l'EULA
> (https://aka.ms/MinecraftEULA) à l'installation : `helm install … --set minecraft.acceptEula=true`.

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
