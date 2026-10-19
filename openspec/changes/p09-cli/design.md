# Design

## Decisions

1. **picocli** + client HTTP JDK + Jackson ; modèles REST partagés avec `ksmc-api` (pas de génération de client).
2. Commandes de modules : au démarrage, si le contexte courant est joignable, `GET /api/v1/cli/commands` (cache 10 min dans `~/.ksmc/cache`)
   → sous-commandes picocli construites dynamiquement.
3. Console : WebSocket JDK (`java.net.http.WebSocket`), JLine pour la saisie.
4. Distribution : `ksmc-cli.jar` + scripts `ksmc` / `ksmc.cmd` dans un zip de release, image `ghcr.io/reietsuu/ksmc-cli`.
5. **Choix d'implémentation** :
   - Pas de dépendance à `ksmc-api` (elle tire le client fabric8, lourd et lent à démarrer) : la CLI manipule le JSON
     de l'API (Jackson `JsonNode`) ; dépendances : picocli, Jackson (+ YAML), JLine.
   - Commandes de modules : nouvelle route agrégée `GET /api/v1/cli/commands` côté controller (un seul appel).
   - Codes de sortie : 0 succès, 1 usage (options, contexte absent), 2 API ou réseau ; erreurs sur stderr.
   - Session renouvelée automatiquement sur 401 (jeton de renouvellement du contexte, enregistré) ; `--server`,
     `--token` ou `$KSMC_TOKEN` remplacent le contexte (scripts, CI). `$KSMC_CONFIG` change le fichier de contextes.
   - Commandes génériques par type de ressource (list/get/apply/edit/delete) ; `get` affiche le YAML par défaut ;
     `apply -f` crée puis remplace si la ressource existe ; `edit` utilise `$VISUAL`/`$EDITOR` et `If-Match`.

