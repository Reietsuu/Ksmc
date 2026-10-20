# Design

## Decisions

1. React 18 + TypeScript + Vite ; TanStack Query (cache API) ; React Router ; feuille CSS globale + variables CSS (thème clair/sombre, `data-theme`) ;
   Monaco Editor chargé à la demande pour YAML/fichiers ; xterm.js pour la console ; i18next.
2. Client API typé généré depuis `openapi.json` (`openapi-typescript`) au build → cohérence avec p08.
3. Temps réel : une connexion WebSocket d'événements qui invalide les requêtes TanStack concernées.
4. Build : sous-projet Gradle `:dashboard` avec des tâches `Exec` (`npmInstall`, `npmBuild`, `npmTest` branché sur `check`)
   qui utilisent le npm du système (Node ≥ 20, présent sur les runners GitHub) : pas de plugin Gradle ajouté
   (node-gradle n'est pas garanti compatible Gradle 9). `dist/` est copié dans les ressources `/dashboard` du controller ;
   le controller sert `/` en statique et renvoie `index.html` pour toute URL inconnue hors `/api/` et `/modules/`.
5. Modules : `GET /api/v1/ui/modules` (déjà fourni par p06/p08) → `import()` dynamique du bundle du module, `mount(el, api)`.
6. Tests : Vitest (composants) + Playwright (e2e navigateur sur kind, quelques parcours critiques).
7. Session : jetons dans `localStorage`, renouvellement automatique sur 401 ; WebSocket authentifiés par `?access_token=`.
8. Dépendances npm (justification) : react/react-dom, react-router-dom, @tanstack/react-query, i18next/react-i18next,
   openapi-fetch + openapi-typescript (client typé), @xterm/xterm (+ addon-fit), monaco-editor + @monaco-editor/react
   (Monaco empaqueté localement, pas de CDN), yaml (éditeur YAML). Tests : vitest, jsdom, @testing-library/*,
   @playwright/test (parcours navigateur).
9. Parcours Playwright (`dashboard/e2e`) lancés par `DashboardE2eTest` (dernier scénario du job e2e) : le test Java
   prépare les données (service Velocity, template storage, module hello), exécute `npx playwright test` contre la
   redirection de port du controller puis vérifie les effets côté cluster. Captures dans `dashboard/e2e/screenshots`,
   publiées comme artefact CI `dashboard-playwright`. La console utilise `glist` (Velocity démarre plus vite que Paper).
