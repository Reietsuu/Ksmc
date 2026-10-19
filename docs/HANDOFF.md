# Handoff (état de reprise)

Dernière mise à jour : 2026-10-15

- **Mergé dans `main`** : p01 (PR #1), p02 (PR #2), p03 (PR #3) — CI verte, changes archivés.
- **Terminé, CI en cours** : p04-messaging sur `feat/p04-messaging` (PR #4) — merger quand vert puis archiver.
- **En cours** : p05-bridge-networking sur `feat/p05-bridge-networking` (empilée sur p04).
- **À faire (noté)** : injection automatique de FabricProxy-Lite (+ Fabric API) pour Fabric en forwarding
  moderne → avec l'injection des bridges (p05 tâche 1.3).
- **Limites connues** : `service.deleted` n'est publié que pour les services supprimés par leur task
  (pas de finalizer, pour ne jamais bloquer une suppression si le controller est absent).

## Pièges connus de la machine de dev

- Port hôte 25565 occupé par un haproxy de l'utilisateur et :8080 par un autre service → ne jamais
  publier de port hôte par défaut ; `scripts/e2e-kind.sh` utilise un kubeconfig dédié `.tools/kubeconfig-ksmc`.
- Toujours `export KUBECONFIG=$PWD/.tools/kubeconfig-ksmc` ; supprimer le cluster kind après usage.
- Helm 4 : `--skip-crds` après `kubectl apply --server-side -f deploy/helm/ksmc/crds/` (sinon conflit SSA).
- `gh` 2.46 : pas de `--json` sur `gh pr checks` → `gh run list -b <branche>`.
- Les tâches longues (e2e) doivent être lancées avec l'arrière-plan de l'outil, pas `&` (tuées sinon).
- Avant d'attendre un `exit=` dans `e2e.log`, SUPPRIMER le log : sinon on lit le résultat de l'exécution précédente.
- JetStream du chart : stockage fichier uniquement (pas de buckets KV en mémoire).
- jnats tire `bcprov-lts8on` : exclu dans controller (conflit fabric8 mock) et wrapper (inutile, mots de passe).
- Paper `latest` (26.x) et Velocity 4.x exigent Java 25 : l'e2e charge les images runtime java21 et java25.
- Tests des projets Java 8 : compilés en Java 17 → pas de `getFirst()`.
- fabric8 7.9.0 est aligné sur JOSDK 5.6.2 : ne pas monter fabric8 seul.
