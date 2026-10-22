# Design

## Context

Le bridge (dans la JVM du serveur) envoie toutes les 5 s un `ServiceReport` sur NATS (`ksmc.<ns>.report.<service>`).
Deux abonnés : le controller (`BridgeHub` → bucket `services`, `CloudService.status` au plus toutes les 2 s) et le
wrapper du même Pod (`CloudLink` → `ServiceMetrics`, rendu de `/metrics` sur le port 8090). Le rapport contient déjà
tas et GC (`heapUsedBytes`, `heapMaxBytes`, `gcCount`, `gcTimeMillis`), aujourd'hui seulement rendus par le wrapper.
`ModuleMetrics` (controller, Micrometer) fixe la forme d'API pour les modules. `ksmc-plugin-api` est en Java 8, sans
dépendance.

## Goals / Non-Goals

**Goals :** API de métriques pour plugins sans dépendance ni port supplémentaire ; même chemin que les données
existantes (rapport NATS → wrapper) ; cardinalité bornée ; ajouts compatibles avec les anciens bridges/plugins.
**Non-Goals :** histogrammes à seaux, métriques de plugins sur le controller, historique dans le dashboard.

## Decisions

1. **API** : `Ksmc.api().metrics(String plugin)` renvoie un `Metrics` lié à ce plugin (identifiant validé
   `[a-z][a-z0-9_]*`, préfixe `ksmc_plugin_<plugin>_`). Méthodes calquées sur `ModuleMetrics` :
   `counter(name, tags...)`, `gauge(name, Supplier<? extends Number>, tags...)` et `timer(name, tags...)` avec
   `record(Duration)` et `record(long, TimeUnit)` (tout existe en Java 8, aucune dépendance). Un même nom avec les
   mêmes étiquettes renvoie la même instance. Identifiant explicite plutôt que deviné : les plateformes n'offrent
   pas toutes une façon fiable de retrouver le plugin appelant.
2. **Transport : dans le rapport existant.** `ServiceReport.metrics` = liste d'échantillons
   `{name, type (counter|gauge|timer), tags, value | count+sum}` remplie par le bridge à chaque rapport (5 s) ; les
   jauges sont lues à ce moment (dans un `try`, une jauge qui lève est ignorée et loguée une fois). Taille du
   message bornée par la limite de séries. *Alternative écartée* : un second port HTTP dans le bridge, qui
   imposerait un deuxième ServiceMonitor et un port de plus par Pod, et ne marcherait pas sur tous les serveurs.
3. **Rendu par le wrapper.** `ServiceMetrics` rend les échantillons du dernier rapport (< 60 s, comme les autres
   métriques du bridge) avec les étiquettes du service (`namespace`, `service`, `task`) ajoutées ; `# TYPE` correct
   (`counter`, `gauge`, `summary` pour les timers). Limite : 1000 séries ; tri stable par nom puis étiquettes et
   coupe au-delà ; `ksmc_service_plugin_series_dropped` = séries écartées du dernier rapport. Les noms et
   valeurs d'étiquettes sont échappés au format texte Prometheus.
4. **Mémoire/GC vers le statut** : `CloudServiceStatus` gagne `heapUsedBytes`, `heapMaxBytes`, `gcCount`,
   `gcTimeMillis` (CRD régénérée, `checkCrds`) ; `BridgeHub` les recopie avec le débit existant (pas plus d'une
   écriture / 2 s / service). `ServiceInfo` (bucket `services`) les porte aussi, d'où `CloudServiceInfo` dans la
   plugin-api (nouveau constructeur ; l'ancien reste, valeurs `null`). Colonnes `kubectl` inchangées
   (lisibilité) ; visible dans `-o yaml`, REST et dashboard.
5. **Dashboard** : bloc « Ressources » du détail d'un service (TPS, tas utilisé/max en Mo/Go avec barre, temps de GC
   cumulé), masqué si inconnu. Grafana : panneau tableau `topk(20, {__name__=~"ksmc_plugin_.*"})` par service.
6. **Tests** : unitaires plugin-api (validation des noms, réutilisation des instances), bridge (échantillons dans le
   rapport, jauge qui lève), wrapper (rendu, échappement, limite + compteur d'écartés), controller (recopie du
   statut) ; dashboard (vitest) ; e2e : `plugin-hello` déclare un compteur, on le lit sur `/metrics` du service.

## Risks / Trade-offs

- **Cardinalité** (étiquette par joueur…) → limite dure de 1000 séries et métrique d'écartés ; doc avec mise en garde.
- **Fraîcheur 5 s** → suffisant pour Prometheus (scrape 15–30 s) ; documenté.
- **Taille du rapport NATS** → ~100 o par série, 1000 séries ≈ 100 Ko toutes les 5 s au pire, sous la limite NATS
  (1 Mo) ; à surveiller si la limite est relevée plus tard.
