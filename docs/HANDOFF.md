# Handoff (état de reprise)

Dernière mise à jour : 2026-10-19

- **Mergé dans `main`** : p01 → p07 (PR #1 à #7), CI verte, changes archivés.
- **Suivant** : p08-rest-api sur `feat/p08-rest-api`.
- **Provisoire (p06)** : API protégée par un jeton admin unique (Secret `ksmc-api-token`, `StaticTokenAuthenticator`) ;
  p08 doit le remplacer par les comptes (interface `ApiAuthenticator`). Le dashboard (p10) doit fournir `mount(el, api)` avec `api.fetch(path)`.
- **Limites connues** : `service.deleted` n'est publié que pour les services supprimés par leur task
  (pas de finalizer, pour ne jamais bloquer une suppression si le controller est absent).
- **Sécurité à revoir** : tous les services partagent l'utilisateur NATS `service` ; un service peut publier les
  rapports (joueurs, état) d'un autre service. Envisager des permissions NATS par service (p08/p11).
- **À nettoyer** : warnings 404 du controller quand il patche le statut d'une ressource déjà supprimée
  (sans effet, bruit dans les logs). CI : chaque push d'une branche feat/ lance la CI deux fois
  (push + pull_request) — changement du workflow refusé par les permissions, à faire par l'utilisateur.

## Pièges connus de la machine de dev

- **NE PAS lancer l'e2e complet en local** : les serveurs de l'utilisateur occupent ~17 Go sur 22 ;
  le 2026-10-19 l'e2e a saturé la machine (load 256, RAM 0, mongodb unhealthy). E2E = GitHub Actions
  uniquement ; en local, au plus un cluster kind pour un diagnostic court, supprimé juste après.

- Port hôte 25565 occupé par un haproxy de l'utilisateur et :8080 par un autre service → ne jamais
  publier de port hôte par défaut ; `scripts/e2e-kind.sh` utilise un kubeconfig dédié `.tools/kubeconfig-ksmc`.
- Toujours `export KUBECONFIG=$PWD/.tools/kubeconfig-ksmc` ; supprimer le cluster kind après usage.
- Helm 4 : `--skip-crds` après `kubectl apply --server-side -f deploy/helm/ksmc/crds/` (sinon conflit SSA).
- `gh` 2.46 : pas de `--json` sur `gh pr checks` → `gh run list -b <branche>`.
- Les tâches longues (e2e) doivent être lancées avec l'arrière-plan de l'outil, pas `&` (tuées sinon).
- Avant d'attendre un `exit=` dans `e2e.log`, SUPPRIMER le log : sinon on lit le résultat de l'exécution précédente.
- JetStream du chart : stockage fichier uniquement (pas de buckets KV en mémoire).
- jnats tire `bcprov-lts8on` : exclu dans controller (conflit fabric8 mock) et wrapper (inutile, mots de passe).
- BungeeCord 26.x refuse de démarrer sans serveur ni avec `priorities: []` (ajoute « lobby ») : serveur factice `ksmc-placeholder`.
- fabric8 `file().upload()` exige commons-compress et fermer le stdin d'un exec n'envoie pas EOF : l'e2e passe les petits fichiers en base64 dans la commande.
- Paper `latest` (26.x) et Velocity 4.x exigent Java 25 : l'e2e charge les images runtime java21 et java25.
- Tests des projets Java 8 : compilés en Java 17 → pas de `getFirst()`.
- fabric8 7.9.0 est aligné sur JOSDK 5.6.2 : ne pas monter fabric8 seul.
