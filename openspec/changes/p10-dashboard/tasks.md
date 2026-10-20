# Tasks

## 1. Socle

- [x] 1.1 Projet Vite/React/TS, routing, i18n, thème, client API généré depuis OpenAPI, intégration Gradle et image controller ; vérifier `npm run build` et `./gradlew build`
- [x] 1.2 Login, gestion de session, garde de permissions, flux d'événements temps réel ; tests Vitest

## 2. Pages

- [x] 2.1 Vue d'ensemble, tasks et groupes (formulaires + éditeur YAML) ; tests Vitest
- [x] 2.2 Services (liste, détail, console xterm) ; tests Vitest
- [x] 2.3 Templates (explorateur, éditeur Monaco, upload/download) ; tests Vitest
- [x] 2.4 Joueurs, logiciels, modules (dont pages de modules dynamiques), utilisateurs/jetons, audit ; tests Vitest

## 3. Validation

- [x] 3.1 Responsive 375 px et thème sombre ; parcours Playwright (login, création task, console, éditeur de template, page module hello) dans le job e2e ; vérifier en CI
- [x] 3.2 Documenter le dashboard (captures) dans `docs/DASHBOARD.md` ; vérifier que les captures proviennent du run Playwright
