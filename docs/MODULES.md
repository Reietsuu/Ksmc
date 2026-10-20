# Modules et plugins

Ksmc s'étend de deux façons :

- **Module** : un JAR chargé **dans le controller**. Il ajoute des fonctionnalités au cloud : routes REST,
  commandes CLI, pages du dashboard, résolveurs de logiciels, sources de templates, personnalisation des Pods,
  stratégies de scaling, réactions aux événements…
- **Plugin / mod** : tourne **dans un serveur ou un proxy** (Paper, Velocity, Fabric…) et parle au cloud par
  `ksmc-plugin-api` : infos des services, channels, événements, stockage clé-valeur, état du service.

Les deux exemples complets sont dans [`examples/module-hello`](../examples/module-hello) et
[`examples/plugin-hello`](../examples/plugin-hello) ; ce guide les suit pas à pas.

## Dépendances Maven

Les bibliothèques sont publiées à chaque version (`vX.Y.Z`) sur GitHub Packages, groupe `io.ksmc` :

| Artefact | Java | Pour |
|---|---|---|
| `io.ksmc:ksmc-api` | 21 | modules (inclut `ksmc-wire`) |
| `io.ksmc:ksmc-plugin-api` | 8 | plugins et mods (aucune dépendance) |
| `io.ksmc:ksmc-wire` | 8 | types de messagerie (rarement utile seul) |

```kotlin
repositories {
    mavenCentral()
    // GitHub Packages demande un jeton GitHub (scope read:packages), même pour un dépôt public.
    maven("https://maven.pkg.github.com/Reietsuu/Ksmc") {
        credentials { username = System.getenv("GITHUB_ACTOR"); password = System.getenv("GITHUB_TOKEN") }
        content { includeGroup("io.ksmc") }
    }
}
dependencies {
    compileOnly("io.ksmc:ksmc-api:0.1.0")        // module
    compileOnly("io.ksmc:ksmc-plugin-api:0.1.0") // plugin
}
```

Sans jeton : JitPack (`maven("https://jitpack.io")`, `com.github.Reietsuu.Ksmc:ksmc-api:v0.1.0`) ou, depuis un clone
du dépôt, `./gradlew publishToMavenLocal` puis `mavenLocal()`.

Toujours en `compileOnly` : le controller fournit l'API aux modules et le bridge la fournit aux plugins.

Versions : SemVer. Une version majeure de l'API ne change qu'en cas d'incompatibilité ; les parties annotées
`@Experimental` peuvent évoluer dans une version mineure.

## Écrire un module

### 1. Le projet

```
module-hello/
├── build.gradle.kts
└── src/main/
    ├── java/com/example/hello/HelloModule.java
    └── resources/
        ├── ksmc-module.json
        └── web/hello.js          (facultatif : page du dashboard)
```

`build.gradle.kts` : plugin `java`, `options.release = 21`, `compileOnly("io.ksmc:ksmc-api:…")`. Les bibliothèques
que le module utilise en plus doivent être **embarquées** dans son JAR (plugin Gradle `shadow` par exemple).

### 2. Le descripteur `ksmc-module.json`

```json
{
  "id": "hello",
  "name": "Hello",
  "version": "1.0.0",
  "main": "com.example.hello.HelloModule",
  "apiVersion": "^1.0",
  "dependencies": [{"id": "autre-module", "version": "^2.1", "optional": true}]
}
```

| Champ | Rôle |
|---|---|
| `id` | minuscules, chiffres, tirets ; **doit être le nom de la ressource `KsmcModule`** ; préfixe des routes et de la CLI |
| `version` | SemVer, affichée dans `kubectl get mcmod` |
| `main` | classe implémentant `KsmcModule`, avec constructeur public sans argument |
| `apiVersion` | plage de versions de l'API acceptée (défaut : la version majeure courante) ; sinon `IncompatibleApiVersion` |
| `dependencies` | autres modules (plage façon npm : `^1.2`, `~1.2.3`, `1.x`, `>=1.0 <2.0`) ; une dépendance obligatoire absente → `MissingDependency` |

### 3. La classe principale

```java
public final class HelloModule implements KsmcModule {
    private volatile String greeting = "Hello";

    @Override
    public void onEnable(ModuleContext ctx) {
        onConfigChanged(ctx.config());
        ctx.rest().get("hello", "hello.read", req -> Response.ok(Map.of("message", greeting + ", " + req.user() + " !")));
        ctx.registry().register(ServicePodCustomizer.class,
                (service, pod) -> pod.getMetadata().getLabels().put("example.ksmc.io/hello", "true"));
    }

    @Override
    public void onConfigChanged(JsonNode config) {   // KsmcModule.spec.config, sans rechargement
        greeting = config.path("greeting").asText("Hello");
    }
}
```

`onDisable()` est facultatif : **tout ce qui passe par le contexte est retiré automatiquement** (SPI, routes,
commandes, pages, abonnements de messagerie, ressources passées à `registry().onDisable(...)`).

Une exception dans `onEnable` met le module en `Failed` (raison `EnableError`) sans affecter le controller ni les
autres modules ; une exception dans une route renvoie un 500 ; dans `onConfigChanged`, `ConfigError` (l'ancienne
configuration reste active et la mise à jour est retentée).

### 4. Ce que le contexte donne (`ModuleContext`)

| Méthode | Usage |
|---|---|
| `registry().register(Spi.class, impl)` | ajouter une implémentation de SPI (tableau ci-dessous) |
| `registry().onDisable(closeable)` | ressource fermée à la désactivation (thread, client…) |
| `rest()` | routes sous `/api/v1/modules/<id>/…` avec permission |
| `cliCommand(...)` | commande `ksmc <id> <nom>` découverte par la CLI |
| `dashboardPage(...)` | entrée de menu du dashboard |
| `messaging()`, `events()`, `kv(bucket)` | channels, événements cloud, stockage clé-valeur (voir [MESSAGING.md](MESSAGING.md)) |
| `kubernetes()`, `namespace()` | client fabric8 du controller |
| `config()`, `dataDirectory()`, `logger()` | configuration, dossier persistant propre au module, journal |

Points d'extension (SPI) :

| SPI | Effet |
|---|---|
| `SoftwareResolver` | nouveau type de résolveur pour les `SoftwareDefinition` (`spec.resolver.type`) |
| `TemplateSourceProvider` | nouvelle source de `ServiceTemplate` (ex. S3), paramètres dans `spec.extensions` |
| `ServicePodCustomizer` | modifier le Pod des services (labels, sidecars…) ; nom, namespace et labels Ksmc protégés |
| `ScalingStrategy` | stratégie utilisable dans `spec.autoscaling.strategies` |
| `MessagingTransport` | transport utilisé par les modules à la place de NATS (les services restent sur NATS) |

### 5. Routes REST, permissions, CLI et dashboard

```java
ctx.rest().post("greet/{who}", "hello.write", req -> Response.status(201, "Salut " + req.pathParam("who")));
ctx.cliCommand(new CliCommand("broadcast", "Envoie un message", List.of(new CliCommand.Argument("text", "message", true)),
        "POST", "broadcast"));
ctx.dashboardPage(new DashboardPage("Hello", "hand", "web/hello.js"));
```

- Une route répond sur `/api/v1/modules/<id>/<chemin>` (`{nom}` = paramètre). Le corps d'une `Response` est
  sérialisé en JSON (une `String` est envoyée en texte). Après désactivation : 404.
- Permissions : une permission par route (`null` = utilisateur authentifié). Les jokers `hello.*` et `*` sont
  acceptés. Préfixez-les par l'id du module.
- Authentification : comptes de l'API (session ou jeton d'API, voir [API.md](API.md)). Les permissions des routes
  de module s'accordent dans les rôles comme les autres (`PUT /api/v1/roles/<rôle>`), et `GET /api/v1/permissions`
  liste celles des modules actifs.
- CLI : `GET /api/v1/modules/<id>/cli` liste les commandes ; `{arg}` dans le chemin est remplacé par l'argument
  du même nom, les autres arguments passent en paramètres de requête.
- Dashboard : le bundle est un module ES exportant `mount(element, api)`, servi sous `/modules/<id>/ui.js` ;
  `GET /api/v1/ui/modules` liste les pages, ajoutées au menu sans reconstruire le dashboard. `api.fetch(chemin, init)`
  appelle `/api/v1/modules/<id>/<chemin>` avec la session de l'utilisateur, `api.fetchApi('/api/v1/...')` le reste
  de l'API. `mount` peut renvoyer une fonction de nettoyage (ou le module exporter `unmount(element)`).
  Voir [DASHBOARD.md](DASHBOARD.md).

### 6. Construire et installer

```bash
./gradlew build      # → build/libs/ksmc-module-hello.jar
sha256sum build/libs/ksmc-module-hello.jar
```

Puis une ressource `KsmcModule` dont le **nom est l'id du module**, avec une seule source :

```yaml
apiVersion: ksmc.io/v1alpha1
kind: KsmcModule
metadata:
  name: hello
spec:
  source:
    url: https://example.com/ksmc-module-hello.jar   # HTTPS + sha256 obligatoire
    sha256: <empreinte>
    # maven: com.example:ksmc-module-hello:1.0.0     # Maven Central par défaut (mavenRepository pour un autre dépôt),
    #                                                # vérifié par le .sha1 publié
    # store: modules/hello.jar                        # fichier du stockage du controller (/data)
    # builtin: hello                                  # module intégré à l'image (/opt/ksmc/modules/hello.jar)
  enabled: true
  config:
    greeting: Bonjour
```

Copier un JAR dans le stockage du controller (source `store`) :

```bash
POD=$(kubectl -n ksmc get pod -l app.kubernetes.io/component=controller -o name | head -1)
kubectl -n ksmc cp build/libs/ksmc-module-hello.jar ${POD#pod/}:/data/modules/hello.jar -c controller
```

Modules intégrés : `builtinModules` dans les values du chart crée une ressource par entrée
(`hello: {enabled: true, config: {...}}`).

### 7. Cycle de vie

```
kubectl -n ksmc get mcmod
NAME    VERSION   PHASE
hello   1.0.0     Enabled
```

| Phase | Signification |
|---|---|
| `Pending` | téléchargement en cours |
| `Enabled` | chargé et actif |
| `Disabled` | `spec.enabled: false` |
| `Failed` | voir `status.reason` : `ChecksumMismatch`, `IncompatibleApiVersion`, `MissingDependency`, `InvalidDescriptor`, `LoadError`, `EnableError`, `ConfigError`, `DownloadError` |

- Modifier `spec.config` : transmis à `onConfigChanged`, sans rechargement.
- Changer la source (nouvelle URL, nouveau fichier `store`…) : le module est rechargé ; les modules qui en
  dépendent sont désactivés puis réactivés automatiquement (sous 30 s).
- Supprimer la ressource : le module est désactivé (sous 15 s).

### 8. Isolation et sécurité

Chaque module a son propre classloader. Il voit : le JDK, l'API Ksmc (`io.ksmc.api`, `io.ksmc.wire`), SLF4J,
Jackson et fabric8 (types utilisés par le contexte), ses propres classes et celles de ses dépendances déclarées
(non transitives). Les classes internes du controller et ses autres bibliothèques sont invisibles : deux modules
peuvent embarquer deux versions différentes d'une même bibliothèque.

**Ce n'est pas un bac à sable** : un module s'exécute dans le controller avec ses droits Kubernetes
(lecture des Secrets du namespace, création de Pods…). N'installez que des modules de confiance.

## Écrire un plugin (ou un mod)

Le bridge Ksmc est ajouté automatiquement à chaque service et fournit `ksmc-plugin-api` (non relocalisé).

### 1. Déclarer la dépendance au bridge

| Plateforme | Déclaration |
|---|---|
| Bukkit / Paper / Folia | `plugin.yml` : `depend: [KsmcBridge]` |
| Velocity | `@Plugin(dependencies = @Dependency(id = "ksmc-bridge"))` |
| BungeeCord / Waterfall | `bungee.yml` : `depends: [KsmcBridge]` |
| Fabric | `fabric.mod.json` : `"depends": {"ksmc-bridge": "*"}` |
| NeoForge | `neoforge.mods.toml` : dépendance `modId = "ksmcbridge"` |

### 2. Utiliser l'API

```java
KsmcPluginApi api = Ksmc.get();                       // IllegalStateException hors Ksmc (Ksmc.isAvailable())
api.currentService().name();                          // "bedwars-3"
api.services(ServiceQuery.task("bedwars").state("LOBBY"));   // cache local, immédiat
api.state().setState("INGAME");                       // → CloudService.status.state
api.state().setProperty("map", "Desert");             // → CloudService.status.properties.map

api.channels().subscribe("hello", m -> m.reply("reçu"));     // reçoit les messages destinés à ce service
api.channels().send("task:lobby", "hello", "Bonjour");       // cibles : all, service:x, task:x, environment:x, controller
api.channels().request("controller", "stats", "players", null, 3000)
        .thenAccept(reply -> getLogger().info(reply.message()));   // thread principal

api.events().subscribe("service.*", e -> getLogger().info(e.type() + " " + e.subject()));
api.keyValue("shared").put("season", "3");
api.players().thenAccept(players -> ...);
```

Fils d'exécution :

- les futurs (`CompletableFuture`), les événements et les surveillances KV sont rappelés sur le **thread principal**
  (Bukkit : ordonnanceur ; Folia : région globale ; Fabric/NeoForge : file du serveur ; proxies : ordonnanceur
  du proxy) ;
- les gestionnaires de channels sont appelés sur un thread de messagerie, pour pouvoir répondre aussitôt :
  utilisez `api.mainThread().execute(...)` pour toucher au monde du jeu ;
- les abonnements faits avant la connexion du bridge au cloud sont appliqués dès qu'elle est établie ; les envois
  et requêtes échouent tant que `api.isConnected()` est faux.

### 3. Installer le plugin

Comme tout fichier de serveur : dans un `ServiceTemplate` (dossier `plugins/`) référencé par la task. Par exemple
avec le stockage du controller :

```bash
kubectl -n ksmc cp build/libs/ksmc-plugin-hello.jar ${POD#pod/}:/data/templates/hello-plugin/plugins/ksmc-plugin-hello.jar -c controller
```

```yaml
apiVersion: ksmc.io/v1alpha1
kind: ServiceTemplate
metadata: {name: hello-plugin}
spec:
  storage: {path: hello-plugin}
---
# Dans la ServiceTask :
#   templates: [{name: hello-plugin}]
```

## Essayer les exemples

```bash
./gradlew -p examples/module-hello build
./gradlew -p examples/plugin-hello build
```

Puis : installer le module (source `store` ci-dessus), créer une task Paper avec le template du plugin, et :

```bash
kubectl -n ksmc port-forward svc/ksmc-controller 8080 &
# Session admin (voir docs/API.md pour la première connexion et les jetons d'API).
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<mot de passe>"}' | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/modules/hello/hello
curl -X POST -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/modules/hello/broadcast?text=Salut"
kubectl -n ksmc logs <service> | grep '\[hello\]'      # « [hello] reçu : Salut »
```

Le scénario e2e `ModuleSystemE2eTest` déroule exactement ces étapes.
