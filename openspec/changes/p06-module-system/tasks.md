# Tasks

## 1. Hôte de modules

- [x] 1.1 `KsmcModule`, `ModuleContext`, descripteur `ksmc-module.json`, `Registry` à désenregistrement automatique dans `ksmc-api` ; tests unitaires
- [x] 1.2 Chargement : classloaders isolés, tri des dépendances, vérification d'API, confinement des exceptions ; tests avec JARs de test générés
- [ ] 1.3 Reconciler `KsmcModule` : sources (HTTPS+sha256, Maven, store), phases, configuration à chaud, rechargement ; modules intégrés via values ; tests mock server
- [ ] 1.4 Extensions REST (préfixe + permissions), descripteurs CLI, bundles dashboard, branchement de toutes les SPI existantes ; tests

## 2. API plugins

- [ ] 2.1 `ksmc-plugin-api` (Java 8) : point d'entrée, infos services/tasks/joueurs, channels, requêtes, événements, KV, état ; implémentation dans `ksmc-bridge-common` ; tests
- [ ] 2.2 Adaptateurs de scheduler par plateforme (Bukkit, Velocity, Bungee, Fabric, NeoForge) ; vérifier build de tous les bridges

## 3. Distribution et exemples

- [ ] 3.1 Publication Maven (GitHub Packages) de `ksmc-api`, `ksmc-wire`, `ksmc-plugin-api` ; `jitpack.yml` ; vérifier `./gradlew publishToMavenLocal`
- [ ] 3.2 `examples/module-hello` et `examples/plugin-hello` ; scénario e2e : installation du module, route REST, rechargement de config, checksum invalide ; plugin-hello reçoit un message de channel
- [ ] 3.3 Guide `docs/MODULES.md` (créer un module/plugin pas à pas, SPI, permissions) ; vérifier que le pas-à-pas correspond aux exemples
