# API REST

L'API du controller pilote tout le cloud ; la CLI, le dashboard et les modules l'utilisent. Elle est servie par
le Service `ksmc-controller` (port 8080) sous `/api/v1`.

- Description OpenAPI 3 : `GET /api/v1/openapi.json` (sans authentification), Swagger UI : `/api/docs`.
- Ressources : même forme que les ressources Kubernetes (`metadata`, `spec`, `status`). Une modification faite par
  l'API est une modification Kubernetes, et l'inverse : `kubectl` et l'API voient toujours la même chose.
- Erreurs : JSON uniforme `{"code": "...", "message": "...", "details": {...}}` (`Unauthorized`, `Forbidden`,
  `NotFound`, `Conflict`, `InvalidRequest`, `TooManyRequests`, `PasswordChangeRequired`...).

Les exemples utilisent `curl` et `jq`, avec l'API redirigée en local :

```bash
kubectl -n ksmc port-forward svc/ksmc-controller 8080 &
KSMC=http://localhost:8080
```

Les blocs marqués `# e2e` sont exécutés tels quels par le scénario e2e `RestApiE2eTest`.

## Authentification

### Première connexion

L'installation crée le compte `admin` ; son mot de passe initial est dans le Secret `ksmc-admin` et doit être
changé à la première connexion (en attendant, toute autre requête répond 403 `PasswordChangeRequired`) :

```bash
INITIAL=$(kubectl -n ksmc get secret ksmc-admin -o jsonpath='{.data.password}' | base64 -d)
FIRST=$(curl -s $KSMC/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$INITIAL\"}" | jq -r .accessToken)
curl -s $KSMC/api/v1/auth/password -H "Authorization: Bearer $FIRST" -H 'Content-Type: application/json' \
  -d "{\"currentPassword\":\"$INITIAL\",\"newPassword\":\"un-mot-de-passe-solide\"}"
```

### Sessions

`POST /api/v1/auth/login` renvoie un jeton d'accès (15 minutes) et un jeton de renouvellement (7 jours) :

```bash
# e2e
SESSION=$(curl -sf $KSMC/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$SESSION" | jq -r .accessToken)
REFRESH=$(echo "$SESSION" | jq -r .refreshToken)
curl -sf $KSMC/api/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq -e '.name == "admin"'
```

Avant expiration, `POST /api/v1/auth/refresh` donne une nouvelle session ; changer son mot de passe invalide les
jetons de renouvellement existants :

```bash
# e2e
TOKEN=$(curl -sf $KSMC/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}" | jq -r .accessToken)
```

Après 10 échecs de connexion en une minute pour un compte, les tentatives suivantes reçoivent 429 (en-tête
`Retry-After`) pendant au moins une minute.

### Jetons d'API

Pour les scripts et la CI : un jeton nommé, révocable, avec expiration facultative. Il n'est affiché qu'à sa
création et s'utilise comme une session (`Authorization: Bearer ksmc_...`) :

```bash
# e2e
CREATED=$(curl -sf $KSMC/api/v1/auth/tokens -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"ci","expiresInDays":30}')
API_TOKEN=$(echo "$CREATED" | jq -r .token)
TOKEN_ID=$(echo "$CREATED" | jq -r .id)
curl -sf $KSMC/api/v1/tasks -H "Authorization: Bearer $API_TOKEN" > /dev/null
curl -sf -X DELETE $KSMC/api/v1/auth/tokens/$TOKEN_ID -H "Authorization: Bearer $TOKEN"
test "$(curl -s -o /dev/null -w '%{http_code}' $KSMC/api/v1/tasks -H "Authorization: Bearer $API_TOKEN")" = 401
```

## Comptes, rôles et permissions

Les droits sont des permissions nommées `ksmc.<ressource>.<action>`, avec jokers (`*`, `ksmc.service.*`),
regroupées en rôles :

| Rôle | Permissions |
|---|---|
| `admin` | `*` |
| `operator` | tasks, groupes, templates, services (y compris console et commandes), joueurs ; lecture des logiciels, modules, événements |
| `viewer` | lecture seule (tasks, groupes, templates, services, logiciels, modules, joueurs, événements) |

Comptes, rôles, audit et écriture des logiciels et des modules sont réservés à `admin` (`ksmc.account.admin`,
`ksmc.audit.read`, `ksmc.software.write`, `ksmc.module.write`). Les modules ajoutent leurs permissions
(`<module>.<action>`) : `GET /api/v1/permissions` les liste.

```bash
# e2e
curl -sf $KSMC/api/v1/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"lecteur","password":"lecteur-mot-de-passe","roles":["viewer"],"mustChangePassword":false}'
VIEWER=$(curl -sf $KSMC/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"lecteur","password":"lecteur-mot-de-passe"}' | jq -r .accessToken)
# Un viewer ne peut pas supprimer : 403 avec la permission manquante.
curl -s -X DELETE $KSMC/api/v1/services/lobby-1 -H "Authorization: Bearer $VIEWER" | jq -e '.code == "Forbidden"'
curl -sf -X PUT $KSMC/api/v1/roles/support -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"permissions":["ksmc.service.*","ksmc.task.read"]}'
curl -sf -X PUT $KSMC/api/v1/accounts/lecteur/roles -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"roles":["support"]}'
curl -sf -X DELETE $KSMC/api/v1/accounts/lecteur -H "Authorization: Bearer $TOKEN"
```

| Requête | Rôle |
|---|---|
| `GET/POST /api/v1/accounts`, `DELETE /api/v1/accounts/<nom>` | lister, créer (changement de mot de passe exigé par défaut), supprimer |
| `PUT /api/v1/accounts/<nom>/roles`, `.../password` | rôles, réinitialisation du mot de passe |
| `GET /api/v1/roles`, `PUT/DELETE /api/v1/roles/<nom>` | rôles |

## Ressources

`/api/v1/tasks`, `/groups`, `/templates`, `/services`, `/software`, `/modules` :

| Requête | Effet |
|---|---|
| `GET /api/v1/<type>` | liste paginée : `?limit=` (1–500, défaut 100), `?continue=` (curseur renvoyé), `?labelSelector=`, `?task=` |
| `POST /api/v1/<type>` | création (201, en-têtes `Location` et `ETag`) |
| `GET /api/v1/<type>/<nom>` | lecture ; `ETag` = `resourceVersion` |
| `PUT /api/v1/<type>/<nom>` | remplacement de `spec`, `labels`, `annotations` |
| `PATCH /api/v1/<type>/<nom>` | modification partielle (JSON merge patch) |
| `DELETE /api/v1/<type>/<nom>` | suppression |

`status` est en lecture seule. Avec `If-Match: <ETag>`, une écriture sur une ressource modifiée entre-temps répond
409 `Conflict` (concurrence optimiste).

```bash
# e2e
curl -sf $KSMC/api/v1/tasks -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "metadata": {"name": "doc-lobby"},
  "spec": {"environment": "server", "software": {"name": "paper", "version": "latest"}, "minServiceCount": 0}
}' > /dev/null
kubectl -n ksmc get mctask doc-lobby
ETAG=$(curl -sf -D - -o /dev/null $KSMC/api/v1/tasks/doc-lobby -H "Authorization: Bearer $TOKEN" \
  | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p')
curl -sf -X PATCH $KSMC/api/v1/tasks/doc-lobby -H "Authorization: Bearer $TOKEN" -H "If-Match: $ETAG" \
  -H 'Content-Type: application/json' -d '{"spec":{"maxServiceCount":10}}' | jq -e '.spec.maxServiceCount == 10'
# Même ETag une seconde fois : 409.
curl -s -X PATCH $KSMC/api/v1/tasks/doc-lobby -H "Authorization: Bearer $TOKEN" -H "If-Match: $ETAG" \
  -H 'Content-Type: application/json' -d '{"spec":{"maxServiceCount":12}}' | jq -e '.code == "Conflict"'
curl -sf "$KSMC/api/v1/tasks?limit=10" -H "Authorization: Bearer $TOKEN" | jq -e '.items | length >= 1'
curl -sf -X DELETE $KSMC/api/v1/tasks/doc-lobby -H "Authorization: Bearer $TOKEN"
```

Joueurs connectés : `GET /api/v1/players`, `GET /api/v1/players/<nom ou uuid>`.

## Actions

| Requête | Permission | Effet |
|---|---|---|
| `POST /api/v1/tasks/<task>/start` `{"count": 2}` | `ksmc.task.start` | démarre 2 services en plus ; renvoie leurs noms (plafonné par `maxServiceCount`) |
| `POST /api/v1/tasks/<task>/maintenance` `{"enabled": true}` | `ksmc.task.write` | maintenance |
| `POST /api/v1/services/<service>/stop` · `start` · `restart` | `ksmc.service.write` | arrêt, démarrage (statique), redémarrage |
| `POST /api/v1/services/<service>/command` `{"command": "list"}` | `ksmc.service.command` | commande console |
| `POST /api/v1/services/<service>/deploy` `{"template": "lobby/backup"}` | `ksmc.service.command` + `ksmc.template.write` | copie le dossier du service dans un template `storage` |
| `POST /api/v1/players/<joueur>/send` `{"task": "lobby"}` (ou `service`) | `ksmc.player.action` | envoie le joueur |
| `POST /api/v1/players/<joueur>/kick` `{"reason": "..."}` · `message` `{"text"}` · `title` `{"title","subtitle"}` | `ksmc.player.action` | expulse, message, titre |

```bash
curl -s $KSMC/api/v1/tasks/lobby/start -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"count": 2}'
# {"services":["lobby-3","lobby-4"],"requested":2,"started":2}
curl -s $KSMC/api/v1/services/lobby-1/command -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"command": "say Redémarrage dans 5 minutes"}'
```

## Fichiers des templates

Pour les templates `storage` (chemin pouvant contenir des `/`, ex. `lobby/default`) :

| Requête | Effet |
|---|---|
| `GET /api/v1/templates/<chemin>/files` | liste (chemin, taille, date) |
| `GET` · `PUT` · `DELETE /api/v1/templates/<chemin>/files/<fichier>` | lire, écrire (corps brut), supprimer (fichier ou dossier) |
| `PATCH .../files/<fichier>` `{"path": "nouveau/nom"}` | renommer |
| `GET /api/v1/templates/<chemin>/archive` | télécharger en ZIP |
| `PUT /api/v1/templates/<chemin>/archive` | téléverser un ZIP ou tar.gz (remplace le contenu ; `?mode=merge` pour fusionner) |

```bash
# e2e
curl -sf -X PUT $KSMC/api/v1/templates/doc/default/files/server.properties -H "Authorization: Bearer $TOKEN" \
  --data-binary 'motd=Bienvenue'
curl -sf $KSMC/api/v1/templates/doc/default/files/server.properties -H "Authorization: Bearer $TOKEN" | grep -q Bienvenue
curl -sf $KSMC/api/v1/templates/doc/default/archive -H "Authorization: Bearer $TOKEN" -o /tmp/ksmc-doc.zip
curl -sf -X DELETE $KSMC/api/v1/templates/doc/default/files/server.properties -H "Authorization: Bearer $TOKEN"
```

Les prochains services utilisant le template reçoivent les fichiers modifiés.

## Console et logs

- `GET /api/v1/services/<service>/console` (WebSocket, `ksmc.service.console`) : les 200 dernières lignes
  (`?lines=`), puis chaque nouvelle ligne. Chaque message texte envoyé est une commande (avec
  `ksmc.service.command`, sinon la console est en lecture seule). Messages reçus en JSON :
  `{"type":"log","line":"..."}`, `{"type":"command","command":"list","result":"ok"}`, `{"type":"error",...}`.
- `GET /api/v1/services/<service>/logs?lines=500` : logs actuels en texte ; `&previous=true` : démarrage précédent
  (tant que le Pod existe).
- `GET /api/v1/services/<service>/lastlog` : les 500 dernières lignes du dernier échec, conservées même après le
  remplacement du service.

Les navigateurs ne pouvant pas envoyer d'en-tête sur un WebSocket, le jeton peut y être passé en
`?access_token=<jeton>` (accepté seulement pour les WebSocket et les flux SSE).

```bash
# Avec websocat (https://github.com/vi/websocat) :
websocat "ws://localhost:8080/api/v1/services/lobby-1/console?access_token=$TOKEN"
```

## Événements

Flux temps réel des événements du cloud (voir [MESSAGING.md](MESSAGING.md)), avec `ksmc.event.read`. Filtres :
`type` (`service.*`, `player.login`, `>`...), `task`, `environment`, `since` (numéro de séquence).

- WebSocket : `GET /api/v1/events?type=service.*` (un message JSON par événement).
- Server-Sent Events : `GET /api/v1/events/stream?type=service.*` ; reprise après coupure avec l'en-tête
  `Last-Event-ID` (envoyé automatiquement par `EventSource`).

```bash
curl -N "$KSMC/api/v1/events/stream?type=service.*" -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream'
# event: service.started
# id: 42
# data: {"type":"service.started","subject":"lobby-3",...}
```

## Audit

Chaque écriture est journalisée (utilisateur, action, cible, résultat, heure) : logs JSON du controller (logger
`ksmc.audit`), 1000 dernières entrées dans le ConfigMap `ksmc-audit`, et les modules peuvent exporter via la SPI
`AuditSink`.

```bash
# e2e
curl -sf "$KSMC/api/v1/audit?action=tasks.delete&limit=5" -H "Authorization: Bearer $TOKEN" \
  | jq -e '.items[0].target == "doc-lobby" and .items[0].user == "admin"'
```
