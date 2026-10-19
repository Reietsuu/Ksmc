# Tasks

## 1. Accès

- [x] 1.1 `AccountStore` (Secret), argon2id, admin initial + changement obligatoire, rôles par défaut, permissions avec joker et permissions de modules ; tests unitaires
- [x] 1.2 Login/refresh JWT, jetons d'API (création, liste, révocation), limitation de débit, filtre d'autorisation ; tests HTTP

## 2. API

- [x] 2.1 Endpoints CRUD tasks/groupes/logiciels/modules/services (server-side apply, ETag, erreurs, pagination) ; tests HTTP avec mock server
- [ ] 2.2 Actions métier (start, stop, restart, command, deploy, maintenance, actions joueurs) ; tests
- [ ] 2.3 Fichiers de templates (CRUD, archive) ; tests
- [ ] 2.4 OpenAPI + Swagger UI ; flux événements WebSocket/SSE ; audit ; tests

## 3. Console et validation

- [ ] 3.1 WebSocket console (logs + commandes) et extrait `lastlog` des échecs ; tests
- [ ] 3.2 Scénarios e2e REST : login admin, création task par REST visible par kubectl, viewer refusé (403), commande console, flux d'événements ; vérifier en CI
- [ ] 3.3 Documenter l'API et l'authentification dans `docs/API.md` (avec exemples curl) ; vérifier les exemples curl via e2e
