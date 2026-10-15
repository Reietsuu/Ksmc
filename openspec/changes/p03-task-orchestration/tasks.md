# Tasks

## 1. Groupes et configuration effective

- [x] 1.1 Résolution des groupes (environnement + référencés) et fusion (listes, maps, podTemplate) en `EffectiveConfig` + hash ; tests unitaires couvrant chaque règle
- [ ] 1.2 Reconciler `ServiceGroup` qui redéclenche les tasks concernées ; `status.effective` et condition `GroupNotFound` ; tests mock server

## 2. Reconciler ServiceTask

- [ ] 2.1 SPI `ScalingStrategy` + stratégie `static` ; allocation du plus petit numéro libre ; choix des services à arrêter ; tests unitaires
- [ ] 2.2 Création/suppression des `CloudService`, statiques (redémarrage même nom, PVC conservé), `enabled`, `maintenance`, demandes manuelles, refus `MaxServiceCountReached` ; tests mock server
- [ ] 2.3 Remplacement des échecs avec fenêtre d'échecs + backoff + condition `Degraded` ; tests unitaires avec horloge simulée
- [ ] 2.4 `updateStrategy` (`Recreate`, `RollingUpdate`, `OnRestart`) + `progressDeadlineSeconds` ; tests unitaires
- [ ] 2.5 Statut agrégé, sous-ressource scale, colonnes kubectl ; tests unitaires

## 3. Validation

- [ ] 3.1 Scénarios e2e : task min 2 → 2 Running ; `kubectl scale` 2→1 ; réutilisation de `lobby-1` ; task statique conserve le monde ; rolling update de version ; groupe manquant ; vérifier en CI
- [ ] 3.2 Compléter `docs/USAGE.md` (tasks, groupes, statiques, mises à jour) ; vérifier que les exemples sont appliqués par l'e2e
