# Tasks

## 1. Cœur commun

- [x] 1.1 `ksmc-bridge-common` : identité, cache services (RPC + événements), sélection fallback/forced hosts ; tests unitaires
- [x] 1.2 Controller : RPC `services.list`, réception `service.info` → status (débit limité), relais événements joueurs ; tests
- [x] 1.3 Injection automatique du bridge dans le manifest selon le logiciel, `bridge.enabled` ; test unitaire

## 2. Proxies

- [ ] 2.1 `ksmc-bridge-velocity` : enregistrement dynamique, fallback, forced hosts, maintenance, ping global, registre joueurs KV, actions joueurs ; tests unitaires adaptateur
- [ ] 2.2 `ksmc-bridge-bungee` (BungeeCord/Waterfall) : mêmes fonctions ; tests unitaires

## 3. Serveurs

- [ ] 3.1 `ksmc-bridge-bukkit` (API 1.8.8, testé sur Paper récent et Spigot 1.8.8 en e2e) : état, propriétés, actions
- [ ] 3.2 `ksmc-bridge-fabric` (dernière MC stable) et `ksmc-bridge-forge` (NeoForge, dernière MC stable) : état + actions ; vérifier build
- [ ] 3.3 Registre joueurs côté controller (lecture KV), actions envoyer/expulser/message/titre via RPC ; tests

## 4. Exposition et validation

- [ ] 4.1 Service K8s par task proxy (type, annotations, policies, PROXY protocol), NetworkPolicy optionnelle dans le chart ; tests mock server
- [ ] 4.2 Scénarios e2e : proxy Velocity + 2 lobbies ; client de test (bot protocole minimal) se connecte via NodePort et atterrit sur le lobby le moins rempli ; nouveau serveur visible en < 5 s ; registre joueur ; même chose avec BungeeCord ; vérifier en CI
- [ ] 4.3 Documenter réseau, fallback, bridges et compatibilité versions dans `docs/USAGE.md` ; vérifier exemples via e2e
