# Handoff (état de reprise)

Dernière mise à jour : 2026-10-15

- **Fait** : p01-foundation mergé dans `main` (PR #1), CI verte (build, specs, e2e kind).
- **En cours** : p02-service-runtime sur la branche `feat/p02-service-runtime` (aucune tâche commencée).

## Pièges connus de la machine de dev

- Le port hôte 25565 est occupé par un haproxy de l'utilisateur et :8080 par un autre service → ne jamais
  publier de port hôte par défaut ; `scripts/e2e-kind.sh` utilise un kubeconfig dédié `.tools/kubeconfig-ksmc`.
- Pas de contexte kubectl global : toujours `export KUBECONFIG=$PWD/.tools/kubeconfig-ksmc`.
- Supprimer le cluster kind après usage (`.tools/bin/kind delete cluster --name ksmc`) : la machine héberge d'autres services.
- Helm 4 : installer avec `--skip-crds` après `kubectl apply --server-side -f deploy/helm/ksmc/crds/` (sinon conflit SSA).
- `gh` 2.46 : pas de `--json` sur `gh pr checks` → utiliser `gh run list`.
- fabric8 7.9.0 est aligné sur JOSDK 5.6.2 : ne pas monter fabric8 seul.
