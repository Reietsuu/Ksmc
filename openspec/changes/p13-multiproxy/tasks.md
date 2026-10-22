# Tasks

## 1. Session unique par joueur

- [x] 1.1 `KsmcBridge` : verrou de session à la connexion (`create`/`update` CAS sur le bucket `players`, règle « session orpheline », politique `deny`), `playerLogin` réutilise l'entrée ; tests unitaires (KV en mémoire avec révisions, connexions concurrentes) ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [x] 1.2 Politique `replace` (RPC `KICK` vers l'ancien proxy, attente ≤ 3 s) et expulsion d'une session perdue dans `refreshPlayers` ; tests unitaires ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [x] 1.3 `KsmcVelocityBridge.onLogin` : appliquer le verrou (refus avec `ksmc.duplicate-login.message`) ; doc des propriétés dans `docs/` (page proxies) ; vérifier `./gradlew :ksmc-bridge-velocity:build -q`
- [ ] 1.4 E2E : deux proxies Velocity, le même bot se connecte sur les deux, la seconde connexion est refusée ; vérifier en CI (`gh run watch`)
- [ ] 1.5 Commit + push (`feat(bridge): single session per player across proxies`)

## 2. Commandes réseau

- [x] 2.1 `ksmc-wire` : sujet `BridgeSubjects.broadcast(ns)` ; `KsmcBridge` s'y abonne et relaie aux joueurs via `Platform.messagePlayer`/nouvelle méthode `broadcast` ; tests ; vérifier `./gradlew :ksmc-wire:test :ksmc-bridge-common:test -q`
- [ ] 2.2 `NetworkCommands` (commun) : `glist [task]`, `find`, `send <joueur> <service|task>`, `alert`, permissions `ksmc.command.<nom>`, messages d'erreur ; tests unitaires avec `FakePlatform` ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [ ] 2.3 Velocity : enregistrement des commandes + `/ksmc <sous-commande>`, remplacement des intégrées `glist`/`send` selon `ksmc.commands.replace-builtin` ; vérifier `./gradlew :ksmc-bridge-velocity:build -q`
- [ ] 2.4 Docs (commandes, permissions, option de remplacement) + scénario dans `docs/TESTING.md` ; E2E : `/glist` envoyé à la console d'un proxy compte les bots des deux proxies ; vérifier en CI
- [ ] 2.5 Commit + push (`feat(bridge): network-wide proxy commands`)

## 3. Transfert à l'arrêt d'un proxy

- [ ] 3.1 `TransferPlan` (commun) : transfert si protocole ≥ 766, autre proxy `Running` de la task et `ksmc.transfer.enabled` ≠ `false`, adresse = `ksmc.transfer.address` ou hôte virtuel ; sinon kick avec `ksmc.transfer.fallback-message` ; tests unitaires ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [ ] 3.2 `KsmcBridge` : mode drainage (délai `ksmc.transfer.delay-ms`, retrait local sans `disconnect` KV pour les transférés) ; tests ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [ ] 3.3 Velocity : drainage dans `onShutdown` avant `bridge.close()` via `Player.transferToHost` ; `ConfigPatcher` écrit `accepts-transfers = true` (test dans `ConfigPatcherTest`) ; vérifier `./gradlew :ksmc-bridge-velocity:build :ksmc-wrapper:test -q`
- [ ] 3.4 Docs (transfert, versions client, adresse, désactivation) + `docs/TESTING.md` ; E2E : réduction 2 → 1 de la task proxy, le bot 1.8 reçoit le message de repli ; vérifier en CI
- [ ] 3.5 Commit + push (`feat(bridge): transfer players when a proxy stops`)

## 4. Plusieurs tasks proxy

- [ ] 4.1 `networkPlayers()` : portée `ksmc.player-count.scope` (`network` | `task`) ; tests unitaires ; vérifier `./gradlew :ksmc-bridge-common:test -q`
- [ ] 4.2 E2E : deux tasks proxy (NodePort distincts), maintenance sur une seule, lobby partagé, compteur limité à la task ; vérifier en CI
- [ ] 4.3 Docs : exemple `examples/` à deux tasks proxy + section dans la page proxies ; vérifier `kubectl apply --dry-run=client -f examples/<fichier>`
- [ ] 4.4 Commit + push (`feat(proxy): multiple proxy tasks with per-task player count`)

## Workflow follow-up

- Merger `feat/p13-multiproxy` dans `main` une fois la CI verte, puis archiver le change (`openspec archive p13-multiproxy`).
- Change suivant : bridge BungeeCord/Waterfall multi-proxy en réutilisant `NetworkCommands`, `TransferPlan` et le verrou de session.
