# Design

## Context

SPI `ScalingStrategy` (p03), joueurs par service connus via le bridge (p05).

## Decisions

1. Stratégies composables : `spec.autoscaling.strategies: [players, schedule]` ; la cible = max des propositions, bornée.
2. Mémoire des décisions (dernier scale, horodatage du dernier joueur par service) dans `status.autoscaling` pour survivre aux redémarrages.
3. Services préparés : label `ksmc.io/prepared=true` ; les bridges proxy les excluent du routage ; promotion = retrait du label.
4. Cron : bibliothèque `cron-utils` (format Unix), réévaluation chaque minute.
5. Réconciliation périodique de 10 s pour les tasks avec autoscaling, en plus des événements.

## Risks / Trade-offs

- [Joueurs non remontés sans bridge] → fallback sur le nombre de joueurs du Server List Ping.
