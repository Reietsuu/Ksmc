# Design

## Decisions

1. React 18 + TypeScript + Vite ; TanStack Query (cache API) ; React Router ; CSS modules + variables CSS (thème) ;
   Monaco Editor chargé à la demande pour YAML/fichiers ; xterm.js pour la console ; i18next.
2. Client API typé généré depuis `openapi.json` (`openapi-typescript`) au build → cohérence avec p08.
3. Temps réel : une connexion WebSocket d'événements qui invalide les requêtes TanStack concernées.
4. Build : tâche Gradle `:dashboard:npmBuild` (plugin `com.github.node-gradle.node`) dont la sortie est copiée dans les ressources du controller.
5. Modules : `GET /api/v1/dashboard/extensions` → `import()` dynamique du bundle du module, `mount(el, api)`.
6. Tests : Vitest (composants) + Playwright (e2e navigateur sur kind, quelques parcours critiques).
