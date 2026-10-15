# Tasks

## 1. Dépôt et build

- [x] 1.1 Créer `.gitignore`, `LICENSE` (Apache-2.0), `README.md` (présentation + liens docs) ; créer le dépôt public `Reietsuu/Ksmc` avec `gh repo create` et pousser ; vérifier avec `gh repo view`
- [x] 1.2 Générer le wrapper Gradle 9 (via image docker `gradle`), `settings.gradle.kts`, `build-logic` avec conventions `ksmc.java21` / `ksmc.java8`, catalogue `gradle/libs.versions.toml` ; vérifier `./gradlew projects`
- [x] 1.3 Créer les projets vides `ksmc-api`, `ksmc-wire`, `ksmc-controller`, `ksmc-e2e` ; vérifier `./gradlew build -q`
- [x] 1.4 Ajouter `scripts/tools.sh` qui installe kind, kubectl et helm dans `.tools/` (versions épinglées) ; vérifier `.tools/kind version`

## 2. Modèle de ressources

- [x] 2.1 POJOs `ServiceTask` (spec, status, scale), `ServiceGroup`, `ServiceTemplate`, `CloudService`, `SoftwareDefinition`, `KsmcModule` + types communs (`Condition`, `extensions`, `PodTemplateOverride`) ; vérifier `./gradlew :ksmc-api:compileJava`
- [x] 2.2 Annotations de validation, colonnes `additionalPrinterColumns`, noms courts ; tests unitaires de sérialisation ; vérifier `./gradlew :ksmc-api:test`
- [x] 2.3 Tâche `generateCrds` → `deploy/helm/ksmc/crds/*.yaml` + tâche `checkCrds` (diff) ; vérifier `./gradlew generateCrds checkCrds`
- [x] 2.4 Exemples `examples/*.yaml` (une ressource de chaque type, dont task Paper `lobby` et task Velocity `proxy`) ; vérifier qu'ils passent `kubectl apply --dry-run=server` dans l'e2e (tâche 4.3)

## 3. Controller minimal et chart

- [x] 3.1 `ksmc-controller` : main, config par variables d'environnement, client fabric8, JOSDK démarré sans reconciler, Javalin avec `/healthz` et `/readyz` (API K8s + NATS) ; test unitaire de readiness ; vérifier `./gradlew :ksmc-controller:test`
- [x] 3.2 `docker/controller.Dockerfile` ; vérifier `docker build -f docker/controller.Dockerfile .`
- [x] 3.3 Chart Helm : CRDs, ServiceAccount + ClusterRole/Role, Deployment controller, Service, dépendance NATS (JetStream), `values.yaml` documenté ; vérifier `helm lint` et `helm template`
- [x] 3.4 Documenter l'installation dans `docs/INSTALL.md` ; vérifier que les commandes du doc fonctionnent dans l'e2e

## 4. CI et e2e

- [ ] 4.1 Workflow `ci.yml` : build + tests + `checkCrds` + `openspec validate --all` + `helm lint` ; pousser et vérifier `gh run watch` vert
- [ ] 4.2 Workflow `images.yml` : build/push `ksmc-controller` sur GHCR depuis `main` (tags SHA + `edge`) ; vérifier l'image dans `gh api /users/Reietsuu/packages`
- [ ] 4.3 `scripts/e2e-kind.sh` + projet `ksmc-e2e` avec scénarios : CRDs installées, task invalide refusée, `kubectl scale` modifie `minServiceCount`, exemples acceptés, controller `Ready` ; job `e2e` dans `ci.yml` ; vérifier en CI
- [ ] 4.4 Workflow `release.yml` déclenché par tag `v*` (chart packagé + artefacts) ; vérifier par `act`-free dry-run : `gh workflow view release.yml`

## Workflow follow-up

- Archiver `p01-foundation` quand la CI est verte sur `main`.
