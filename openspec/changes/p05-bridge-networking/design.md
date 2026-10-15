# Design

## Context

Messagerie (p04) disponible. Les bridges utilisent `ksmc-plugin-api` minimal (complété en p06) et le transport NATS relocalisé.

## Goals / Non-Goals

**Goals :** un cœur de bridge commun Java 8 (`ksmc-bridge-common`) et des adaptateurs fins par plateforme.
**Non-Goals :** bridge Forge legacy (1.12 et avant) ; Fabric/NeoForge multi-versions (une ligne MC par release, extensible ensuite).

## Decisions

1. **`ksmc-bridge-common`** (Java 8) : connexion NATS (identité service via variables d'env injectées par le wrapper),
   cache local des services (chargé via RPC `services.list`, mis à jour par événements), choix fallback, registre joueurs côté proxy.
   Adaptateurs : Velocity (API 3.x, Java 17+), Bungee (API Bungee, Java 8), Bukkit (API 1.8.8 compilée, compatible récentes via API stable),
   Fabric (Loom, Fabric API), NeoForge (ModDevGradle). Shadow + relocation de jnats/gson.
2. **Registre joueurs** : bucket KV `players` (clé = UUID) écrit par les proxies avec TTL 30 s rafraîchi ; le controller le lit
   pour REST/dashboard. Cohérence après panne gérée par TTL (spec). Événements joueurs publiés par le controller ? Non :
   par les proxies sur `ksmc.<ns>.pev.>` (sujet joueurs autorisé aux services proxy) et relayés dans le stream d'événements par le controller.
3. **Actions joueurs** : RPC vers le proxy qui héberge le joueur (`ksmc.<ns>.rpc.proxy.<nom>.player`).
4. **Installation du bridge** : le controller ajoute au manifest l'artefact bridge correspondant (servi depuis l'image controller,
   versions alignées) dans `plugins/` ou `mods/`.
5. **Remontée d'état** : bridge → message `service.info` → le controller met à jour `CloudService.status` (débit limité : 1 écriture/2 s/service).
6. **Exposition** : Service K8s possédé par la task proxy, selector `ksmc.io/task=<proxy>` + readiness ; PROXY protocol → config
   `haproxy-protocol`/`proxy_protocol` du proxy via patcher.

## Risks / Trade-offs

- [Fabric/NeoForge cassent souvent entre versions MC] → une ligne supportée, CI qui construit ; versions supplémentaires = travail futur.
- [Charge d'écriture sur l'API K8s due aux états] → limitation de débit + regroupement des patches de status.
