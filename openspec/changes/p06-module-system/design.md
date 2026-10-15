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

## Risks / Trade-offs

- [Fuites de classloader au rechargement] → tests qui chargent/déchargent 20 fois et vérifient le nettoyage des enregistrements.
- [API figée trop tôt] → annotation `@Experimental` pour les parties instables.
