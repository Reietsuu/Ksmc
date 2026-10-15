# Tasks

## 1. Stratégies

- [ ] 1.1 Stratégie `players` (seuil, idle, cooldown, `maxConcurrentStarts`) ; tests unitaires avec horloge simulée
- [ ] 1.2 Stratégie `schedule` (cron + fuseau) et composition ; tests unitaires
- [ ] 1.3 Services préparés (label, promotion, exclusion par les bridges proxy) ; tests unitaires

## 2. Intégration

- [ ] 2.1 `status.autoscaling`, événements `ScaledUp`/`ScaledDown` + `task.scaled`, réconciliation périodique ; tests mock server
- [ ] 2.2 Scénario e2e : bots de test remplissent une task → un service démarre ; départ des bots → arrêt après idle (valeurs courtes) ; vérifier en CI
- [ ] 2.3 Documenter l'autoscaling dans `docs/USAGE.md` ; vérifier les exemples via e2e
