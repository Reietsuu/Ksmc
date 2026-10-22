# Proposal

## Why

Ksmc sait déjà faire tourner plusieurs proxies Velocity derrière un même Service, avec la liste des serveurs, le
compteur de joueurs et le registre des joueurs partagés. Mais chaque proxy reste un îlot pour tout le reste :
un même compte peut être connecté deux fois via deux proxies, les commandes `/glist` et `/send` de Velocity ne
voient que le proxy local, et arrêter ou réduire un proxy expulse ses joueurs. Pour qu'un réseau à plusieurs
proxies se comporte comme un seul, il faut un vrai mode multi-proxy, Velocity d'abord.

## What Changes

- **Anti double connexion** : un UUID ne peut être connecté qu'à un seul proxy du namespace, toutes tasks proxy
  confondues. Politique par task : `deny` (défaut, la nouvelle connexion est refusée) ou `replace` (l'ancienne
  session est expulsée).
- **Commandes réseau** dans le bridge Velocity : `/glist [task]`, `/find <joueur>`, `/send <joueur> <service|task>`,
  `/alert <message>`. Elles agissent sur tout le réseau et, par défaut, remplacent les commandes locales `/glist`
  et `/send` de Velocity. Chaque commande a sa permission `ksmc.command.<nom>`.
- **Arrêt sans coupure** : quand un proxy s'arrête (scale-down, mise à jour, arrêt manuel, suppression du Pod),
  ses joueurs sont transférés vers un autre proxy `Running` de la même task. Les clients 1.20.5+ passent par le
  paquet Transfer, à l'adresse qu'ils avaient utilisée ; les autres sont expulsés avec un message « reconnecte-toi ».
- **Plusieurs tasks proxy** : chaque task proxy garde ses propres réglages (exposition, forced hosts, maintenance,
  maximum de joueurs) et partage les serveurs et le registre des joueurs. Le compteur du ping peut être limité à
  la task (`ksmc.player-count.scope: task`) au lieu de tout le réseau (défaut, comportement actuel).
- La logique commune (verrou de connexion, commandes, plan de transfert) vit dans `ksmc-bridge-common` pour être
  réutilisée telle quelle par le bridge BungeeCord plus tard.

**Hors périmètre** : BungeeCord/Waterfall (même protocole, change ultérieur) ; chat global ou staff-chat entre
proxies ; synchronisation de plugins tiers entre proxies ; transfert *entre* tasks proxy différentes.
**Laissé ouvert** : bot e2e 1.20.5+ pour tester le paquet Transfer de bout en bout (le transfert est couvert par
des tests unitaires et le repli par l'e2e) ; commandes réseau supplémentaires via la même mécanique.

## Capabilities

### New Capabilities
- `multi-proxy` : comportement d'un réseau à plusieurs proxies (session unique par joueur, commandes réseau,
  transfert des joueurs à l'arrêt d'un proxy).

### Modified Capabilities
- `proxy-integration` : la liste de joueurs globale devient réglable par task (portée réseau ou task) ; une
  exigence explicite couvre plusieurs tasks proxy aux réglages indépendants.

## Impact

- `ksmc-bridge-common` : verrou de session dans le bucket KV `players`, sujet NATS de diffusion entre proxies,
  logique des commandes et du plan de transfert (Java 8).
- `ksmc-bridge-velocity` : événement de pré-connexion, commandes, transfert à `ProxyShutdownEvent`.
- `ksmc-wrapper` (`ConfigPatcher`) : `accepts-transfers = true` dans `velocity.toml`.
- `ksmc-wire` : nouveau sujet `BridgeSubjects.broadcast`, type d'action `TRANSFER` éventuel.
- Docs : `docs/` (page réseau/proxies), `TESTING.md`. Aucune nouvelle dépendance ; aucun changement de CRD.
