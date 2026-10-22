# Design

## Context

Existant (p05) : `KsmcBridge` (commun, Java 8) tient le registre local des services (bucket KV `services`), publie
les joueurs dans le bucket KV `players` (clé = UUID, TTL 30 s rafraîchi) et répond aux actions joueurs sur
`ksmc.<ns>.rpc.player.<service>`. `KsmcVelocityBridge` est un adaptateur fin (`Platform`). Plusieurs tasks proxy
existent déjà techniquement : un Service K8s par task (`ProxyExposure`), des propriétés par task (maintenance,
forced hosts, `expose.maxPlayers`). Velocity API 4.2.0 fournit `Player.transferToHost` et `PreLoginEvent`/`LoginEvent` ;
`NatsKeyValue.update(key, value, expectedRevision)` fournit le compare-and-set (`create` quand la révision vaut 0).
Le wrapper reçoit SIGTERM, envoie la commande d'arrêt et attend `stopTimeoutSeconds` (Pod : +15 s de grâce).

## Goals / Non-Goals

**Goals :** toute la logique multi-proxy dans `ksmc-bridge-common`, testable sans Velocity (`FakePlatform`) ;
adaptateur Velocity mince ; aucun changement de CRD, réglages par propriétés de task (`ksmc.*`).
**Non-Goals :** BungeeCord (change ultérieur, réutilisera le cœur) ; transfert entre tasks proxy ; chat global.

## Decisions

1. **Verrou de session = entrée du bucket `players`.** À `LoginEvent` (UUID connu, avant `PostLogin`), le bridge
   lit l'entrée de l'UUID :
   - absente → `create` (révision 0) ; si `create` échoue, un autre proxy vient de la prendre → relire et appliquer la politique ;
   - présente, `proxy` ≠ soi : si ce proxy n'est pas `Running` dans le registre des services, l'entrée est orpheline
     → `update` CAS vers soi ; sinon politique `deny` (refus) ou `replace` (RPC `KICK` vers l'ancien proxy, attente
     de la réponse ≤ 3 s, puis `update` CAS).
   L'entrée écrite à ce moment est l'entrée complète du joueur (proxy = soi, sans serveur) : `playerLogin` devient
   une mise à jour et non plus un `put` aveugle. Le TTL actuel (30 s) reste le filet de sécurité.
   *Alternative écartée* : un bucket `sessions` séparé, qui ferait double emploi avec `players` et pourrait diverger de lui.
   *Défaut si NATS indisponible* : la connexion est acceptée (on n'empêche pas de jouer à cause du cloud), avec un log d'avertissement.
2. **Commandes : cœur commun + enregistrement par plateforme.** `NetworkCommands` (commun) prend
   `(expéditeur, nom, arguments)` et renvoie des lignes de texte ; il lit `players` (KV) et le registre des services,
   et réutilise les RPC existants pour `/send`. L'adaptateur Velocity enregistre `glist`, `find`, `send`, `alert`
   (`SimpleCommand`) et `/ksmc <sous-commande>` ; si `ksmc.commands.replace-builtin` ≠ `false`, il désenregistre
   d'abord les commandes intégrées `glist` et `send`. Permissions : `ksmc.command.<nom>` ; la console a tout.
3. **`/alert` : diffusion NATS.** Nouveau sujet `BridgeSubjects.broadcast(ns)` = `ksmc.<ns>.proxy.broadcast`
   (texte brut, codes `&`). Chaque proxy s'y abonne et l'envoie à ses joueurs. L'événement est aussi utilisable par
   les modules plus tard (pas d'API REST dans ce change).
4. **Transfert à l'arrêt.** Dans `ProxyShutdownEvent` (Velocity attend la fin des écouteurs synchrones), et avant
   `bridge.close()` :
   1. attendre `ksmc.transfer.delay-ms` (défaut 1500) : le Pod terminant est retiré des EndpointSlices, donc la
      reconnexion via le Service ne retombe pas sur lui ;
   2. `TransferPlan` (commun) décide pour chaque joueur : `TRANSFER(adresse)` si le protocole ≥ 766 (1.20.5), si un
      autre proxy `Running` de la même task existe et si `ksmc.transfer.enabled` ≠ `false` ; sinon `KICK(message)` ;
   3. l'adresse vient de `ksmc.transfer.address` (`hôte:port`) si définie, sinon de l'hôte virtuel utilisé par le
      joueur (`Player.getVirtualHost()`), port inclus ;
   4. les joueurs transférés sont retirés du registre local *sans* publier `disconnect` vers le KV (le nouveau proxy
      réécrit l'entrée ; la règle « session orpheline » couvre le reste). `DisconnectEvent` est donc ignoré pendant le drainage.
   Budget : délai + 2 s max, très en dessous de `stopTimeoutSeconds` (30 s par défaut).
   *Alternative écartée* : un preStop hook côté Pod, qui coûte un exec, n'est pas portable et doublonne avec SIGTERM.
5. **Acceptation des transferts.** `ConfigPatcher.velocity` écrit `accepts-transfers = true` (Velocity ≥ 3.3) sauf si
   la task définit `ksmc.transfer.enabled: false`. Sans cette option, Velocity refuserait le client transféré.
6. **Portée du compteur.** `networkPlayers()` lit `ksmc.player-count.scope` : `task` additionne les proxies `Running`
   de `env.task` (`ServiceRegistry.byTask`), sinon tout le réseau (comportement actuel).
7. **Tests.** Unitaires dans `ksmc-bridge-common` : politique de session (KV en mémoire avec révisions),
   `NetworkCommands`, `TransferPlan`, portée du compteur. E2E (kind, CI) avec le `BotClient` 1.8 existant :
   double connexion sur deux proxies, `/glist` via la console d'un proxy (WebSocket console), repli « kick » lors de
   la réduction de la task proxy, deux tasks proxy avec maintenance sur une seule.

## Risks / Trade-offs

- **Transfer non testé de bout en bout** (le bot e2e parle le protocole 1.8) → logique couverte par des tests
  unitaires et repli testé en e2e ; un bot 1.20.5+ est laissé ouvert (voir proposal).
- **Joueurs online-mode et transfert** : le client transféré se ré-authentifie auprès de Mojang ; c'est le
  comportement normal du paquet Transfer, rien à stocker (pas de cookies).
- **Course `replace`** : si l'ancien proxy ne répond pas au KICK sous 3 s, la nouvelle connexion est acceptée et
  l'entrée réécrite ; l'ancienne session finira expulsée au prochain rafraîchissement (le proxy voit que l'entrée
  ne lui appartient plus et expulse le joueur). Ce contrôle est ajouté à `refreshPlayers`.
- **Désenregistrer `/send` et `/glist` de Velocity** peut surprendre ; l'option `ksmc.commands.replace-builtin: false`
  et les commandes `/ksmc …` sont là pour ça.
