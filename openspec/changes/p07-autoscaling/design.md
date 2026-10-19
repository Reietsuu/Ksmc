# Design

## Context

SPI `ScalingStrategy` (p03), joueurs par service connus via le bridge (p05).

## Decisions

1. Stratégies composables : `spec.autoscaling.strategies: [players, schedule]` ; la cible = max des propositions, bornée.
2. Mémoire des décisions (dernier scale, horodatage du dernier joueur par service) dans `status.autoscaling` pour survivre aux redémarrages.
3. Services préparés : label `ksmc.io/prepared=true` ; les bridges proxy les excluent du routage ; promotion = retrait du label.
4. Cron : bibliothèque `cron-utils` (format Unix), réévaluation chaque minute.
5. Réconciliation périodique de 10 s pour les tasks avec autoscaling, en plus des événements.

6. **Choix d'implémentation** :
   - La cible combinée part toujours de `minServiceCount + extraServices` (démarrages manuels conservés quelle que
     soit la liste de stratégies).
   - Cooldown et `maxConcurrentStarts` s'appliquent à la cible combinée (donc aussi aux stratégies des modules) ;
     le minimum de la task et le minimum programmé sont atteints sans délai. Atteindre le minimum de la task
     (création, service perdu) n'est pas une décision d'autoscaling (pas d'événement ni de cooldown).
   - Mémoire des services vides : `status.autoscaling.emptySince` (première observation à 0 joueur), et non
     `CloudService.status.lastPlayerAt`, mis à jour seulement par des patchs espacés et donc possiblement ancien.
   - Désactiver l'autoscaling promeut les services préparés restants (ils deviennent des services normaux).
   - E2E : les joueurs sont simulés par des rapports publiés sur NATS avec l'identité d'un service (chemin exact du
     bridge, bridge désactivé sur la task) ; un client Minecraft 1.21 complet serait fragile et le comptage des
     joueurs par le bridge est déjà couvert par les scénarios p05.

## Risks / Trade-offs

- [Joueurs non remontés sans bridge] → fallback sur le nombre de joueurs du Server List Ping.
