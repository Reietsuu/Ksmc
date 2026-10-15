# Proposal

## Why

Ksmc doit pouvoir gagner de nouvelles fonctionnalités sans modifier son cœur (permissions, signs, stats,
intégrations Discord...), comme les modules de CloudNet. Les développeurs de plugins doivent aussi
communiquer avec le cloud par une API simple et stable.

## What Changes

- Modules du controller : JAR décrit par `ksmc-module.json`, chargé dans un classloader isolé, avec cycle de vie,
  dépendances entre modules, configuration et rechargement à chaud, déclarés par la ressource `KsmcModule`.
- Contexte de module exposant toutes les SPI (`SoftwareResolver`, `TemplateSourceProvider`, `ServicePodCustomizer`,
  `ScalingStrategy`, `MessagingTransport`), messagerie, événements, KV, client Kubernetes, extensions REST/CLI/dashboard,
  permissions et propres CRDs.
- `ksmc-plugin-api` (Java 8) pour les plugins Bukkit/Velocity/Bungee/Fabric/Forge : infos services, joueurs, channels, événements, KV.
- Publication des APIs (Maven via GitHub Packages + JitPack), modèles de projets `examples/module-hello` et `examples/plugin-hello`, guide développeur.

**Hors périmètre** : modules fonctionnels (permissions, signs...) — ce change fournit le socle et un exemple.
**Laissé ouvert** : modules exécutés comme conteneurs séparés (« sidecar modules ») — `KsmcModule.spec.type` réserve la valeur `Container`.

## Capabilities

### New Capabilities
- `module-system` : chargement, cycle de vie, isolation et points d'extension des modules du controller.
- `plugin-api` : API publique offerte aux plugins et mods tournant dans les services.

### Modified Capabilities
- (aucune)

## Impact

`ksmc-api` (contrat public stabilisé), hôte de modules dans `ksmc-controller`, `ksmc-plugin-api`, exemples,
workflow de publication Maven, `docs/MODULES.md`.
