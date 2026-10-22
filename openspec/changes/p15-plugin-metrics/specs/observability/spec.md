## ADDED Requirements

### Requirement: Métriques de plugins sur les services
Le `/metrics` d'un service SHALL exposer les métriques déclarées par ses plugins sous le nom
`ksmc_plugin_<plugin>_<nom>` (`_total` pour un compteur, `_seconds_count`/`_seconds_sum` pour un timer), avec les
étiquettes du service et celles du plugin, dans les 10 s suivant leur mise à jour. Un service SHALL exposer au plus
1000 séries de plugins ; au-delà, les séries supplémentaires SHALL être ignorées et la métrique
`ksmc_service_plugin_series_dropped` SHALL compter les séries écartées.

#### Scenario: Compteur visible dans Prometheus
- **WHEN** le plugin `bedwars` de `bedwars-1` a incrémenté `games` 3 fois
- **THEN** `/metrics` de `bedwars-1` contient `ksmc_plugin_bedwars_games_total` à 3

#### Scenario: Trop de séries
- **WHEN** un plugin crée 1500 séries distinctes
- **THEN** 1000 sont exposées et `ksmc_service_plugin_series_dropped` vaut 500
