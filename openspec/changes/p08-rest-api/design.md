# Design

## Context

Javalin est déjà présent (p01). Les données sont dans les CRDs ; les comptes doivent être persistants sans base de données.

## Decisions

1. **DTO REST = forme des CRDs** (`metadata.name`, `spec`, `status`) pour un mapping trivial et une seule documentation ;
   écritures par server-side apply fabric8, ETag = `resourceVersion`.
2. **OpenAPI** : plugin `javalin-openapi` (annotations) → `/api/v1/openapi.json` + Swagger UI `/api/docs`.
3. **Comptes/rôles/jetons** : stockés dans le Secret `ksmc-accounts` (JSON chiffré au repos par Kubernetes) via un `AccountStore`
   (SPI `AuthProvider` pour OIDC futur). Sessions = JWT signés (clé dans Secret `ksmc-jwt`), 15 min + refresh 7 j.
   Hachage argon2id (`password4j`).
4. **Console** : WebSocket `/api/v1/services/{name}/console` ; logs via `PodResource.watchLog()` fabric8 ; commandes via messagerie (p04).
   Extrait des échecs dans un ConfigMap `<service>-lastlog` possédé par le service.
5. **Événements** : `/api/v1/events` en WebSocket et SSE, branché sur l'EventApi (rejouable avec `?since=`).
6. **Audit** : journal d'audit en logs JSON + 1000 dernières entrées dans un ConfigMap tournant ; SPI `AuditSink` pour export.
7. **CORS** désactivé par défaut (dashboard servi par la même origine).

8. **Choix d'implémentation (1.x)** :
   - JWT HS256 implémenté directement (algorithme imposé, comparaison en temps constant) : pas de bibliothèque JWT.
     Clé : Secret `ksmc-jwt` généré par le chart. Le jeton de renouvellement porte une empreinte du hachage du mot de
     passe : un changement de mot de passe invalide les sessions existantes.
   - Mot de passe initial : Secret `ksmc-admin` (généré par le chart, conservé) ; compte `admin` créé au démarrage s'il
     n'existe pas, avec changement obligatoire (403 `PasswordChangeRequired` sauf `/auth/password` et `/auth/me`).
   - Jetons d'API `ksmc_<id>_<secret>` : seule l'empreinte SHA-256 du secret est stockée ; comptes relus à chaque
     requête (cache 5 s invalidé à chaque écriture) : un rôle retiré ou un jeton révoqué prend effet immédiatement.
   - Limitation : 10 échecs/minute par compte et 50/minute par IP → 429 avec `Retry-After`.
   - Le jeton administrateur provisoire de p06 (Secret `ksmc-api-token`) est supprimé.
   - Rôles par défaut : `admin` (`*`), `operator` (tasks, groupes, templates, services, joueurs + lecture), `viewer`
     (lecture). Comptes, rôles, audit, écriture des logiciels et des modules : `admin` seulement.

## Risks / Trade-offs

- [Secret limité à 1 Mo] → suffisant pour quelques milliers de comptes ; au-delà, `AuthProvider` externe.
