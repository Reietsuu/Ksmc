# Tasks

## 1. Stockage et Template Store

- [x] 1.1 Interface `ArtifactStorage` + implémentation fichier (`/data`) avec écriture atomique ; tests unitaires ; vérifier `./gradlew :ksmc-controller:test`
- [x] 1.2 Sources de templates `storage`, `http`, `configMap`, `git` (JGit) converties en tar.gz cachées par empreinte, SPI `TemplateSourceProvider` ; tests avec dépôt git local et serveur HTTP de test
- [x] 1.3 Endpoints internes : manifest, téléchargement d'artefacts/templates, upload d'artefacts et de déploiements ; authentification TokenReview (audience `ksmc`) ; tests avec mock server

## 2. Catalogue de logiciels

- [x] 2.1 SPI `SoftwareResolver` + `ArtifactPlan` dans `ksmc-api` ; résolveurs `url` et `papermc` (paper, folia, velocity, waterfall) ; tests sur fixtures JSON
- [x] 2.2 Résolveurs `jenkins` (BungeeCord), `fabric-meta`, `forge-installer`, `neoforge-installer`, `buildtools` (Jobs K8s + verrou) ; tests unitaires
- [x] 2.3 Reconciler `SoftwareDefinition` (versions connues dans `status`), table Java par version MC, définitions par défaut dans le chart ; vérifier `helm template` + test unitaire de sélection Java

## 3. Wrapper et images

- [x] 3.1 Projet `ksmc-wrapper` (Java 8) : sous-commande `prepare` (manifest, téléchargement, sha256, extraction, ordre des templates) ; tests unitaires avec serveur HTTP local
- [x] 3.2 `ConfigPatcher` par famille (Paper/Spigot legacy et moderne, Velocity, Bungee, Fabric, Forge) ; tests sur fixtures
- [x] 3.3 Sous-commande `run` : lancement JVM, santé `/live` `/ready` (SLP), stdin, SIGTERM + commande d'arrêt + timeout ; test d'intégration avec un faux serveur
- [ ] 3.4 `docker/runtime.Dockerfile` (args Java 8/17/21/25) ; matrice dans `images.yml` ; vérifier `docker build` local de la variante 21

## 4. Cycle de vie du CloudService

- [ ] 4.1 Reconciler `CloudService` : Pod (initContainer prepare, conteneur run, sondes, ServiceAccount `ksmc-service`, jeton projeté), PVC si statique, Secret de forwarding ; tests mock server
- [ ] 4.2 Fusion `podTemplate` (défauts → groupes → task) et choix d'image runtime ; tests unitaires de fusion
- [ ] 4.3 Calcul des phases/conditions depuis le Pod et la santé, `desiredState`, `restartPolicy`, événements K8s, `deployOnStop` ; tests unitaires de la machine à états
- [ ] 4.4 Scénarios e2e : `CloudService` Paper `latest` atteint `Running` ; Velocity atteint `Running` ; service statique conserve un fichier après recréation ; version inexistante → `Failed` ; vérifier en CI
- [ ] 4.5 Documenter logiciels, templates et services dans `docs/USAGE.md` (exemples YAML) ; vérifier que les exemples sont appliqués par l'e2e
