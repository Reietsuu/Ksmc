# Tasks

## 1. API et transport

- [ ] 1.1 `ksmc-plugin-api` : `Metrics`, `Counter`, `Timer`, `KsmcPluginApi.metrics(plugin)` ; validation des noms ; tests unitaires ; vérifier `./gradlew :ksmc-plugin-api:test -q`
- [ ] 1.2 `ksmc-wire` : `ServiceReport.metrics` (échantillons) + test de compatibilité JSON (`WireCompatibilityTest`) ; vérifier `./gradlew :ksmc-wire:test -q`
- [ ] 1.3 `ksmc-bridge-common` : registre des métriques par plugin (`PluginApi.metrics`), échantillons dans chaque rapport, jauge qui lève ignorée ; tests (`KsmcBridgeTest`) ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [ ] 1.4 Commit + push (`feat(plugin-api): plugin metrics`)

## 2. Exposition

- [ ] 2.1 `ksmc-wrapper` : `ServiceMetrics` rend les échantillons (types, étiquettes du service, échappement), limite 1000 séries, `ksmc_service_plugin_series_dropped` ; tests ; vérifier `./gradlew :ksmc-wrapper:test -q`
- [ ] 2.2 E2E : `examples/plugin-hello` déclare `hello_greetings` ; la valeur apparaît sur `/metrics` du service ; vérifier en CI (`gh run watch`)
- [ ] 2.3 Docs : OBSERVABILITY (métriques de plugins, limite, cardinalité) + exemple dans la doc de la plugin-api ; panneau Grafana ; vérifier `helm template deploy/helm/ksmc | grep ksmc_plugin`
- [ ] 2.4 Commit + push (`feat(observability): expose plugin metrics on services`)

## 3. Mémoire et GC partout

- [ ] 3.1 `CloudServiceStatus` + CRD (heap, GC), recopie par `BridgeHub` ; `ServiceInfo` ; tests controller ; vérifier `./gradlew :ksmc-controller:test checkCrds -q`
- [ ] 3.2 `CloudServiceInfo` (nouveau constructeur, accesseurs) alimenté par le bridge ; tests ; vérifier `./gradlew :ksmc-plugin-api:test :ksmc-bridge-common:test -q`
- [ ] 3.3 Dashboard : bloc « Ressources » du détail d'un service (TPS, tas, GC) ; test vitest ; vérifier `cd dashboard && npm test`
- [ ] 3.4 Docs (USAGE/DASHBOARD) ; E2E : statut REST d'un service Paper contient `heapUsedBytes` ; vérifier en CI
- [ ] 3.5 Commit + push (`feat: heap and GC in status, plugin-api and dashboard`)

## Workflow follow-up

- Merger `feat/p15-plugin-metrics` une fois la CI verte, puis `openspec archive p15-plugin-metrics`.
