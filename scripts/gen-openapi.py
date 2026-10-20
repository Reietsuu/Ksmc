#!/usr/bin/env python3
# Génère ksmc-controller/src/main/resources/api/openapi.json (description de l'API REST).
# Usage : python3 scripts/gen-openapi.py ksmc-controller/src/main/resources/api/openapi.json
# Le test OpenApiDocsTest vérifie que chaque chemin documenté correspond à une route réelle.
import json, sys
R = {"$ref": "#/components/responses/Error"}
def op(summary, perm=None, body=None, ok="200", desc="OK", params=None, tag=None, ok_schema=None, public=False):
    o = {"summary": summary, "tags": [tag] if tag else []}
    if perm:
        o["description"] = "Permission : `%s`." % perm
    if params:
        o["parameters"] = params
    if body:
        o["requestBody"] = {"required": True, "content": {"application/json": {"schema": body}}}
    resp = {ok: {"description": desc}}
    if ok_schema:
        resp[ok]["content"] = {"application/json": {"schema": ok_schema}}
    resp["default"] = R
    o["responses"] = resp
    if public:
        o["security"] = []
    return o
def p(name, where="path", desc=None, required=True):
    d = {"name": name, "in": where, "required": required if where == "path" else False, "schema": {"type": "string"}}
    if desc: d["description"] = desc
    return d
NAME = p("name")
obj = {"type": "object"}
res = {"$ref": "#/components/schemas/Resource"}
lst = {"$ref": "#/components/schemas/ResourceList"}
paths = {}
paths["/api/v1/auth/login"] = {"post": op("Connexion (nom / mot de passe)", body={"type": "object", "required": ["username", "password"], "properties": {"username": {"type": "string"}, "password": {"type": "string"}}}, ok_schema={"$ref": "#/components/schemas/Session"}, tag="auth", public=True)}
paths["/api/v1/auth/refresh"] = {"post": op("Renouveler la session", body={"type": "object", "required": ["refreshToken"], "properties": {"refreshToken": {"type": "string"}}}, ok_schema={"$ref": "#/components/schemas/Session"}, tag="auth", public=True)}
paths["/api/v1/auth/password"] = {"post": op("Changer son mot de passe (nouvelle session)", body={"type": "object", "required": ["currentPassword", "newPassword"], "properties": {"currentPassword": {"type": "string"}, "newPassword": {"type": "string", "minLength": 10}}}, ok_schema={"$ref": "#/components/schemas/Session"}, tag="auth")}
paths["/api/v1/auth/me"] = {"get": op("Utilisateur courant et permissions", tag="auth")}
paths["/api/v1/auth/tokens"] = {"get": op("Jetons d'API (les siens ; ?all=true : tous, ksmc.account.admin)", tag="auth", params=[p("all", "query")]),
    "post": op("Créer un jeton d'API (affiché une seule fois)", body={"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}, "expiresInDays": {"type": "integer"}}}, ok="201", tag="auth")}
paths["/api/v1/auth/tokens/{id}"] = {"delete": op("Révoquer un jeton", ok="204", params=[p("id")], tag="auth")}
paths["/api/v1/accounts"] = {"get": op("Comptes", "ksmc.account.admin", tag="accounts"), "post": op("Créer un compte", "ksmc.account.admin", body={"type": "object", "required": ["name", "password", "roles"], "properties": {"name": {"type": "string"}, "password": {"type": "string"}, "roles": {"type": "array", "items": {"type": "string"}}, "mustChangePassword": {"type": "boolean", "default": True}}}, ok="201", tag="accounts")}
paths["/api/v1/accounts/{name}"] = {"delete": op("Supprimer un compte (et ses jetons)", "ksmc.account.admin", ok="204", params=[NAME], tag="accounts")}
paths["/api/v1/accounts/{name}/roles"] = {"put": op("Rôles d'un compte", "ksmc.account.admin", body={"type": "object", "properties": {"roles": {"type": "array", "items": {"type": "string"}}}}, ok="204", params=[NAME], tag="accounts")}
paths["/api/v1/accounts/{name}/password"] = {"put": op("Réinitialiser un mot de passe (changement exigé)", "ksmc.account.admin", body={"type": "object", "properties": {"password": {"type": "string"}}}, ok="204", params=[NAME], tag="accounts")}
paths["/api/v1/roles"] = {"get": op("Rôles et permissions", "ksmc.account.admin", tag="accounts")}
paths["/api/v1/roles/{name}"] = {"put": op("Créer ou remplacer un rôle", "ksmc.account.admin", body={"type": "object", "properties": {"permissions": {"type": "array", "items": {"type": "string"}}}}, ok="204", params=[NAME], tag="accounts"),
    "delete": op("Supprimer un rôle inutilisé", "ksmc.account.admin", ok="204", params=[NAME], tag="accounts")}
paths["/api/v1/permissions"] = {"get": op("Permissions intégrées et des modules", tag="accounts")}
kinds = [("tasks", "task"), ("groups", "group"), ("templates", "template"), ("services", "service"), ("software", "software"), ("modules", "module")]
listparams = [p("limit", "query", "1–500, défaut 100"), p("continue", "query", "curseur de la page suivante"), p("labelSelector", "query"), p("task", "query")]
for path, k in kinds:
    r, w = "ksmc.%s.read" % k, "ksmc.%s.write" % k
    paths["/api/v1/" + path] = {"get": op("Lister (%s)" % path, r, params=listparams, ok_schema=lst, tag=path),
        "post": op("Créer", w, body=res, ok="201", ok_schema=res, tag=path)}
    ifm = p("If-Match", "header", "ETag attendu (409 Conflict sinon)")
    paths["/api/v1/%s/{name}" % path] = {"get": op("Lire (ETag = resourceVersion)", r, params=[NAME], ok_schema=res, tag=path),
        "put": op("Remplacer (spec, labels, annotations)", w, body=res, params=[NAME, ifm], ok_schema=res, tag=path),
        "patch": op("Modifier (JSON merge patch)", w, body=obj, params=[NAME, ifm], ok_schema=res, tag=path),
        "delete": op("Supprimer", w, ok="204", params=[NAME, ifm], tag=path)}
paths["/api/v1/tasks/{name}/start"] = {"post": op("Démarrer N services en plus (noms renvoyés)", "ksmc.task.start", body={"type": "object", "properties": {"count": {"type": "integer", "default": 1}}}, ok="202", params=[NAME], tag="tasks")}
paths["/api/v1/tasks/{name}/maintenance"] = {"post": op("Maintenance", "ksmc.task.write", body={"type": "object", "properties": {"enabled": {"type": "boolean"}}}, params=[NAME], tag="tasks")}
for a, s in [("stop", "Arrêter"), ("start", "Démarrer (service statique arrêté)"), ("restart", "Redémarrer")]:
    paths["/api/v1/services/{name}/%s" % a] = {"post": op(s, "ksmc.service.write", ok="202", params=[NAME], tag="services")}
paths["/api/v1/services/{name}/command"] = {"post": op("Commande console", "ksmc.service.command", body={"type": "object", "properties": {"command": {"type": "string"}}}, ok="202", params=[NAME], tag="services")}
paths["/api/v1/services/{name}/deploy"] = {"post": op("Sauvegarder le dossier du service dans un template storage", "ksmc.service.command + ksmc.template.write", body={"type": "object", "properties": {"template": {"type": "string"}}}, ok="202", params=[NAME], tag="services")}
paths["/api/v1/services/{name}/console"] = {"get": op("Console (WebSocket) : 200 dernières lignes puis le direct ; messages texte = commandes", "ksmc.service.console (+ ksmc.service.command pour écrire)", params=[NAME, p("lines", "query"), p("access_token", "query")], ok="101", desc="Switching Protocols", tag="services")}
paths["/api/v1/services/{name}/logs"] = {"get": op("Logs (texte) ; ?previous=true : démarrage précédent", "ksmc.service.read", params=[NAME, p("lines", "query"), p("previous", "query")], tag="services")}
paths["/api/v1/services/{name}/lastlog"] = {"get": op("Extrait conservé du dernier échec", "ksmc.service.read", params=[NAME], tag="services")}
paths["/api/v1/players"] = {"get": op("Joueurs connectés", "ksmc.player.read", tag="players")}
paths["/api/v1/players/{player}"] = {"get": op("Joueur (nom ou UUID)", "ksmc.player.read", params=[p("player")], tag="players")}
for a, b in [("send", {"service": {"type": "string"}, "task": {"type": "string"}}), ("kick", {"reason": {"type": "string"}}), ("message", {"text": {"type": "string"}}), ("title", {"title": {"type": "string"}, "subtitle": {"type": "string"}})]:
    paths["/api/v1/players/{player}/" + a] = {"post": op("Action joueur : " + a, "ksmc.player.action", body={"type": "object", "properties": b}, params=[p("player")], tag="players")}
TP = p("template", desc="chemin du template storage (peut contenir des /)")
paths["/api/v1/templates/{template}/files"] = {"get": op("Fichiers d'un template storage", "ksmc.template.read", params=[TP], tag="template-files")}
FP = p("file")
paths["/api/v1/templates/{template}/files/{file}"] = {"get": op("Lire un fichier", "ksmc.template.read", params=[TP, FP], tag="template-files"),
    "put": op("Écrire un fichier (corps brut)", "ksmc.template.write", ok="201", params=[TP, FP], tag="template-files"),
    "patch": op("Renommer : {\"path\": \"nouveau\"}", "ksmc.template.write", body={"type": "object", "properties": {"path": {"type": "string"}}}, params=[TP, FP], tag="template-files"),
    "delete": op("Supprimer un fichier ou un dossier", "ksmc.template.write", ok="204", params=[TP, FP], tag="template-files")}
paths["/api/v1/templates/{template}/archive"] = {"get": op("Télécharger en ZIP", "ksmc.template.read", params=[TP], tag="template-files"),
    "put": op("Téléverser un ZIP / tar.gz (remplace ; ?mode=merge pour fusionner)", "ksmc.template.write", params=[TP, p("mode", "query")], tag="template-files")}
paths["/api/v1/events"] = {"get": op("Événements (WebSocket)", "ksmc.event.read", params=[p("type", "query"), p("task", "query"), p("environment", "query"), p("since", "query"), p("access_token", "query")], ok="101", desc="Switching Protocols", tag="events")}
paths["/api/v1/events/stream"] = {"get": op("Événements (Server-Sent Events, reprise par Last-Event-ID)", "ksmc.event.read", params=[p("type", "query"), p("task", "query"), p("environment", "query"), p("since", "query")], tag="events")}
paths["/api/v1/audit"] = {"get": op("Journal d'audit (plus récent d'abord)", "ksmc.audit.read", params=[p("user", "query"), p("action", "query"), p("target", "query"), p("limit", "query")], tag="audit")}
paths["/api/v1/admin/loglevel"] = {"get": op("Niveaux de log définis (logger → niveau)", "ksmc.admin.logs", tag="admin"),
    "put": op("Changer un niveau à chaud : {\"logger\": \"io.ksmc\", \"level\": \"DEBUG\"} (racine si logger absent, level null = hérité)", "ksmc.admin.logs", body={"type": "object", "properties": {"logger": {"type": "string"}, "level": {"type": "string", "enum": ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF"], "nullable": True}}}, tag="admin")}
paths["/api/v1/modules/{id}/cli"] = {"get": op("Commandes CLI déclarées par un module", params=[p("id")], tag="modules")}
paths["/api/v1/cli/commands"] = {"get": op("Commandes CLI de tous les modules actifs (découverte par la CLI)", tag="modules")}
paths["/api/v1/ui/modules"] = {"get": op("Pages du dashboard fournies par les modules", tag="modules")}
paths["/api/v1/openapi.json"] = {"get": op("Cette description", tag="meta", public=True)}
spec = {
  "openapi": "3.0.3",
  "info": {"title": "Ksmc API", "version": "1.0.0", "description": "API du cloud Minecraft Ksmc. Authentification : `Authorization: Bearer <jeton>` (session obtenue par `/api/v1/auth/login` ou jeton d'API `ksmc_…`). Erreurs : `{code, message, details}`. Voir docs/API.md."},
  "servers": [{"url": "/"}],
  "security": [{"bearer": []}],
  "paths": paths,
  "components": {
    "securitySchemes": {"bearer": {"type": "http", "scheme": "bearer"}},
    "schemas": {
      "Error": {"type": "object", "required": ["code", "message"], "properties": {"code": {"type": "string", "example": "Forbidden"}, "message": {"type": "string"}, "details": {"type": "object"}}},
      "Session": {"type": "object", "properties": {"accessToken": {"type": "string"}, "refreshToken": {"type": "string"}, "tokenType": {"type": "string"}, "expiresIn": {"type": "integer"}, "mustChangePassword": {"type": "boolean"}}},
      "Resource": {"type": "object", "description": "Forme des ressources Kubernetes Ksmc (voir les CRD)", "properties": {"metadata": {"type": "object", "properties": {"name": {"type": "string"}, "labels": {"type": "object"}, "annotations": {"type": "object"}, "resourceVersion": {"type": "string", "readOnly": True}}}, "spec": {"type": "object"}, "status": {"type": "object", "readOnly": True}}},
      "ResourceList": {"type": "object", "properties": {"items": {"type": "array", "items": {"$ref": "#/components/schemas/Resource"}}, "continue": {"type": "string", "nullable": True}}}
    },
    "responses": {"Error": {"description": "Erreur", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}}}
  }
}
json.dump(spec, open(sys.argv[1], "w"), ensure_ascii=False, indent=1)
print(len(paths), "chemins")
