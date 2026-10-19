# Design

## Context

Les SPI sont introduites progressivement en p02–p05 dans `ksmc-api`. Ce change les rassemble derrière un contexte de module.

## Goals / Non-Goals

**Goals :** écrire un module = un projet Gradle de 1 classe + 1 JSON ; API stable ; défaillances confinées.
**Non-Goals :** sandbox de sécurité (un module a les droits du controller — documenté) ; modules conteneurs (réservé).

## Decisions

1. **Classloaders** : parent = classloader « API » ne laissant passer que `io.ksmc.api.*`, `io.ksmc.wire.*`, SLF4J, Jackson
   annotations ; graphe de dépendances résolu par tri topologique ; versions SemVer.
2. **Interface** `KsmcModule { void onEnable(ModuleContext ctx); default void onDisable(); default void onConfigChanged(JsonNode cfg) }`.
   `ModuleContext` : `registry()` (enregistrement SPI avec désenregistrement automatique), `messaging()`, `events()`, `kv()`,
   `kubernetes()` (client fabric8), `rest()` (routes Javalin préfixées + permissions), `cli()` (descripteurs), `dashboard()`
   (bundle JS + entrée de menu), `config()`, `dataDirectory()`, `logger()`.
3. **Commandes CLI déclaratives** : un module déclare `{name, args, httpMethod, path}` ; la CLI (p09) les découvre via
   `GET /api/v1/modules/<id>/cli` → pas besoin de mettre à jour la CLI pour un nouveau module.
4. **Dashboard** : un module fournit un bundle ES module servi sous `/modules/<id>/ui.js` exportant `mount(el, api)` ;
   le dashboard (p10) l'ajoute au menu.
5. **Plugin API** : `Ksmc.get()` (point d'entrée statique) retournant `KsmcPluginApi` ; futurs `CompletableFuture`
   (Java 8) ; callbacks repostés sur le thread principal via un `Scheduler` fourni par l'adaptateur de plateforme.
6. **Publication** : Gradle `maven-publish` vers GitHub Packages à chaque release + compatibilité JitPack (`jitpack.yml`).
7. **Exemples** : `examples/module-hello` (route REST, commande CLI, page dashboard, écouteur d'événement) et
   `examples/plugin-hello` (Bukkit : commande `/cloud` listant les services et envoyant un message de channel).

8. **Choix d'implémentation (1.1)** :
   - Les routes REST des modules utilisent des types propres (`RestRoutes.Request/Response`) et non ceux de Javalin :
     l'API des modules ne dépend pas de la version de Javalin du controller (montée de version sans casser les modules).
   - `ksmc-api` dépend de `ksmc-wire` (Java 8, sans dépendance) pour exposer `MessagingTransport`, `EventApi`, `KeyValueApi`.
   - Version de l'API : `KsmcApiVersion.CURRENT` = 1.0.0 ; `apiVersion` absent du descripteur = `^<majeure courante>.0`.
   - Plages de versions façon npm (`^`, `~`, `1.x`, comparateurs) implémentées dans `VersionRange` (pas de dépendance).
   - SPI ajoutée : `ServicePodCustomizer`. Le module s'enregistre via `ExtensionRegistry` (scope par module).

9. **Choix d'implémentation (1.2–1.3)** :
   - Classloader parent des modules : JDK + `io.ksmc.api`, `io.ksmc.wire`, `org.slf4j`, `com.fasterxml.jackson`,
     `io.fabric8` (types présents dans `ModuleContext`) ; ordre de recherche : API → JAR du module → JAR des dépendances
     déclarées (non transitif).
   - Le nom de la ressource `KsmcModule` est l'id du module (préfixe REST/CLI) ; un JAR d'un autre id → `InvalidDescriptor`.
   - Pas de finalizer (comme les services) : un balayage toutes les 15 s désactive les modules dont la ressource a disparu.
   - Rechargement d'une dépendance : ses dépendants sont désactivés puis réactivés par leur vérification périodique (30 s).
   - `store` : clé relative à la racine du stockage du controller (ex. `modules/hello.jar`) ; `maven` : dépôt Maven
     Central par défaut, intégrité vérifiée par le `.sha1` publié à côté du JAR.
   - Modules intégrés : `builtinModules` dans les values → une ressource `KsmcModule` `source.builtin` par entrée,
     JAR attendu dans `/opt/ksmc/modules/<id>.jar` de l'image.

## Risks / Trade-offs

- [Fuites de classloader au rechargement] → tests qui chargent/déchargent 20 fois et vérifient le nettoyage des enregistrements.
- [API figée trop tôt] → annotation `@Experimental` pour les parties instables.
