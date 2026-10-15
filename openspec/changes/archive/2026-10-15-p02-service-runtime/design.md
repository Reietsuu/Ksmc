# Design

## Context

p01 fournit CRDs, controller minimal, chart et CI. Ce change rend un `CloudService` exécutable.
Contraintes : beaucoup de clusters n'ont que du stockage RWO (kind : local-path) ; le code dans le Pod
doit tourner en Java 8 pour les serveurs legacy.

## Goals / Non-Goals

**Goals :** un seul chemin de préparation pour tous les logiciels ; wrapper minimal (pas de git, pas de
logique de résolution) ; tout ce qui est coûteux (résolution, build, clone) centralisé et mis en cache par le controller.

**Non-Goals :** HA du controller (le store est sur un PVC RWO → 1 replica), stockage objet (S3) — ajoutable
par la SPI `ArtifactStorage` plus tard.

## Decisions

1. **Le controller est la source des fichiers.** Il publie pour chaque service un *manifest* JSON
   (`GET /internal/v1/services/{ns}/{name}/manifest`) listant : artefact logiciel (URL interne + sha256),
   archives de templates (URLs internes dans l'ordre d'application), patchs de configuration, commande de lancement.
   Toutes les sources (storage, git via JGit, http, configMap) sont converties par le controller en archives
   tar.gz mises en cache par empreinte (commit git, ETag, resourceVersion). Seule la source `image` est gérée
   dans le Pod (initContainer qui copie le dossier dans un `emptyDir`).
   *Alternative écartée* : PVC RWX partagé (indisponible sur beaucoup de clusters) ; wrapper qui clone git (alourdit Java 8).
2. **Authentification Pod → controller par jeton de ServiceAccount projeté** (audience `ksmc`, durée 1 h),
   validé par `TokenReview` ; le nom du Pod dans le jeton identifie le service. Aucun secret à gérer par service.
3. **Résolveurs** : interface SPI `SoftwareResolver` (`ksmc-api`) produisant un `ArtifactPlan`, soit
   `Download(url, sha256?)`, soit `BuildJob(image, command)`. Intégrés : `papermc` (API Fill v3, projets paper/folia/velocity/waterfall),
   `jenkins` (BungeeCord), `buildtools` (Spigot), `fabric-meta`, `forge-installer`, `neoforge-installer`, `url`.
   Les `BuildJob` sont des Jobs Kubernetes qui téléversent le résultat sur `PUT /internal/v1/artifacts/...`.
4. **Wrapper Java 8** (`ksmc-wrapper.jar`, sans dépendance sauf Gson) avec deux sous-commandes :
   `prepare` (initContainer : télécharge, extrait, applique les patchs) et `run` (conteneur principal :
   lance la JVM, serveur HTTP santé `com.sun.net.httpserver`, ping SLP, relais stdin, gestion SIGTERM).
   Les patchs de configuration (`server.properties`, `spigot.yml`, `config/paper-global.yml`,
   `velocity.toml`, `config.yml` Bungee, `FabricProxy-Lite.toml`) sont des `ConfigPatcher` par famille, testés unitairement.
5. **Images runtime** : `eclipse-temurin:{8,17,21,25}-jre` + wrapper ; la table version MC → Java est
   dans `SoftwareDefinition.spec.javaVersions` (défauts conformes à la spec) et surchargeable par la task.
6. **Forwarding** : Secret `ksmc-forwarding` créé par namespace ; Velocity en mode `modern`, BungeeCord en `legacy` ;
   pour Fabric le mod FabricProxy-Lite est ajouté automatiquement, pour Forge/NeoForge le mode est configurable
   (`forwarding: modern|legacy|none`) car il dépend du mod installé par l'utilisateur.
7. **Pod** : `restartPolicy: Never` ; c'est le controller qui décide des redémarrages (`spec.restartPolicy` du service :
   `Never|OnFailure|Always`). Fusion `podTemplate` par *strategic merge patch* fabric8.
8. **Stockage controller** : un PVC `ksmc-data` (`/data/templates`, `/data/artifacts`) derrière l'interface `ArtifactStorage`.

## Risks / Trade-offs

- [APIs tierces (PaperMC, Fabric, Modrinth) changent ou limitent le débit] → cache agressif, `User-Agent` explicite,
  résolveur `url` comme solution de secours, tests sur fixtures JSON enregistrées.
- [BuildTools lent (~5 min) et gourmand] → Job unique par version avec verrou (annotation sur la `SoftwareDefinition`).
- [Controller = point de passage des fichiers] → archives servies en streaming ; les services déjà démarrés ne dépendent plus du controller.
- [Formats de config Paper différents selon versions] → patchers versionnés + tests de fixtures pour 1.8, 1.16, 1.20, 1.21.

## Open Questions

- Taille par défaut du PVC `ksmc-data` (proposition : 10 Gi) — ajustable par values.
