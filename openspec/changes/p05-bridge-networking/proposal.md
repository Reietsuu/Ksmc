# Proposal

## Why

Un réseau Minecraft n'est jouable que si les proxies connaissent les serveurs qui apparaissent et
disparaissent, si les joueurs sont dirigés vers un lobby disponible et si le cloud sait qui est connecté où.
C'est le rôle du Bridge de CloudNet.

## What Changes

- Plugins proxy `ksmc-bridge-velocity` et `ksmc-bridge-bungee` (BungeeCord/Waterfall) : enregistrement
  dynamique des serveurs, fallback/lobby, forced hosts, maintenance, liste de joueurs globale.
- Plugins/mods serveur `ksmc-bridge-bukkit` (Spigot/Paper), `ksmc-bridge-fabric`, `ksmc-bridge-forge` (NeoForge) :
  remontée d'état (joueurs, motd, état personnalisé), réception des actions joueurs.
- Le bridge est installé automatiquement dans les services (désactivable par task).
- Registre des joueurs en ligne et actions (envoyer, expulser, message, titre).
- Exposition réseau : Service Kubernetes pour chaque task proxy (`LoadBalancer`, `NodePort`, `ClusterIP`), PROXY protocol optionnel.

**Hors périmètre** : permissions, sanctions, statistiques (futurs modules).
**Laissé ouvert** : bridge pour d'autres proxies (Gate, etc.) via le même protocole documenté.

## Capabilities

### New Capabilities
- `proxy-integration` : comportement des proxies vis-à-vis des serveurs du cloud (enregistrement, routage, maintenance).
- `server-bridge` : intégration dans les serveurs de jeu (état remonté, actions reçues, installation automatique).
- `player-management` : suivi des joueurs connectés et actions sur eux.
- `network-exposure` : exposition des proxies hors du cluster.

### Modified Capabilities
- (aucune)

## Impact

Nouveaux projets bridges (Velocity, Bungee, Bukkit, Fabric via Loom, NeoForge via ModDevGradle) ; controller :
registre joueurs, Service K8s par task proxy, injection du bridge dans le manifest.
