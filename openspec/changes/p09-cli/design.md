# Design

## Decisions

1. **picocli** + client HTTP JDK + Jackson ; modèles REST partagés avec `ksmc-api` (pas de génération de client).
2. Commandes de modules : au démarrage, si le contexte courant est joignable, `GET /api/v1/cli/commands` (cache 10 min dans `~/.ksmc/cache`)
   → sous-commandes picocli construites dynamiquement.
3. Console : WebSocket JDK (`java.net.http.WebSocket`), JLine pour la saisie.
4. Distribution : `ksmc-cli.jar` + scripts `ksmc` / `ksmc.cmd` dans un zip de release, image `ghcr.io/reietsuu/ksmc-cli`.
