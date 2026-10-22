# Handoff (état de reprise)

Dernière mise à jour : 2026-10-22 (p13-multiproxy en cours)

- **En cours : p13-multiproxy** sur `feat/p13-multiproxy` (PR #17, brouillon). Code des 4 groupes écrit et testé en
  unitaire (session unique, commandes réseau, transfert à l'arrêt, tasks proxy multiples). Reste : faire passer
  `MultiProxyE2eTest` en CI puis cocher 1.4/2.4/3.4/4.2 et les tâches commit+push, merger, archiver.
  Le transfert passe par `ProxyPreShutdownEvent` (le `ProxyShutdownEvent` arrive après la déconnexion des joueurs).
  L'e2e utilise un lobby Spigot 1.8.8 (bot protocole 1.8) ; le paquet Transfer n'est testé qu'en unitaire.

- **Mergé dans `main`** : p01 → p09 (PR #1 à #9), CI verte, changes archivés.
- **v0.1.0 publiée** (2026-10-20) : p01 → p12 mergés (PR #1 à #12), tous les changes archivés. Release GitHub
  (chart, CRDs, CLI, bridges, exemples, SHA256SUMS), images ghcr `0.1.0` (controller, cli, runtime java8/17/21/25
  + variantes `-jdk`), artefacts Maven `0.1.0` (GitHub Packages).
- Reste ouvert : p12 1.3 (e2e complet en local) non exécuté, décision du propriétaire. Suite : docs/ROADMAP.md.
- Dashboard : `./gradlew build` exécute `npm ci`/`npm run build`/`npm test` (sous-projet `:dashboard`, npm du système).
  Essai navigateur local possible sans cluster : `vite preview` + Playwright avec `page.route`/`page.routeWebSocket`.
- **Limites connues** : `service.deleted` n'est publié que pour les services supprimés par leur task
  (pas de finalizer, pour ne jamais bloquer une suppression si le controller est absent).
- **Sécurité à revoir** : tous les services partagent l'utilisateur NATS `service` ; un service peut publier les
  rapports (joueurs, état) d'un autre service. Envisager des permissions NATS par service (p08/p11).
- **Corrigé** : `scripts/e2e-upgrade.sh` échouait parfois (« métriques absentes après mise à jour », 2026-10-22)
  sans lien avec le change ; la vérification lit `/metrics` 20 s après le redémarrage du controller.
- E2E Velocity : le limiteur de connexions (3 s par IP) refuse deux connexions rapprochées via port-forward.
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
- Ne pas utiliser `pkill -f <motif>` / `pgrep -f` dans une commande qui contient le motif : tue le shell de l'outil.
- Les tâches longues (e2e) doivent être lancées avec l'arrière-plan de l'outil, pas `&` (tuées sinon).
- Avant d'attendre un `exit=` dans `e2e.log`, SUPPRIMER le log : sinon on lit le résultat de l'exécution précédente.
- JetStream du chart : stockage fichier uniquement (pas de buckets KV en mémoire).
- jnats tire `bcprov-lts8on` : exclu dans controller (conflit fabric8 mock) et wrapper (inutile, mots de passe).
- BungeeCord 26.x refuse de démarrer sans serveur ni avec `priorities: []` (ajoute « lobby ») : serveur factice `ksmc-placeholder`.
- fabric8 `file().upload()` exige commons-compress et fermer le stdin d'un exec n'envoie pas EOF : l'e2e passe les petits fichiers en base64 dans la commande.
- Javalin : les filtres `before` ne s'appliquent pas aux upgrades WebSocket → authentifier dans `wsBeforeUpgrade`.
- E2E : `RestApiE2eTest` (@Order(1)) change le mot de passe admin initial en `e2e-admin-password` ; `ApiClient.admin()` gère les deux cas.
- Paper `latest` (26.x) et Velocity 4.x exigent Java 25 : l'e2e charge les images runtime java21 et java25.
- Tests des projets Java 8 : compilés en Java 17 → pas de `getFirst()`.
- fabric8 7.9.0 est aligné sur JOSDK 5.6.2 : ne pas monter fabric8 seul.
