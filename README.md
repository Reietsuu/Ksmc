# Ksmc — Kubernetes Server Minecraft Cloud

Ksmc est un cloud Minecraft dans l'esprit de [CloudNet](https://github.com/CloudNetService/CloudNet), construit
nativement sur Kubernetes : tasks, groupes, templates, services statiques/dynamiques, bridge proxy/serveurs,
messagerie entre services, modules — avec toute la puissance de Kubernetes (scheduling, volumes, affinités,
autoscaling, observabilité).

> 🚧 En développement. Le suivi se fait avec [OpenSpec](https://github.com/Fission-AI/OpenSpec) : voir `openspec/changes/`.

## Fonctionnalités prévues (v0.1)

- Logiciels : Paper, Folia, Spigot/Bukkit, Velocity, BungeeCord, Waterfall, Fabric, Forge, NeoForge
- Gestion par **kubectl** (CRDs `ksmc.io`), **API REST**, **CLI `ksmc`** et **dashboard web**, toutes cohérentes entre elles
- Templates (stockage intégré, git, http, ConfigMap, image OCI), services statiques persistants
- Bridge : enregistrement dynamique des serveurs dans les proxies, fallback, joueurs, actions
- Messagerie simple (channels, requêtes/réponses, événements, KV) pour plugins et modules
- Système de modules (SPI) pour ajouter de nouvelles fonctionnalités
- Autoscaling par joueurs et par horaire, métriques Prometheus

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Installation](docs/INSTALL.md) · [Utilisation](docs/USAGE.md)
- [API REST](docs/API.md) · [CLI](docs/CLI.md) · [Messagerie](docs/MESSAGING.md) · [Modules et plugins](docs/MODULES.md) · [Dashboard](docs/DASHBOARD.md) · [Observabilité](docs/OBSERVABILITY.md)
- Specs : `openspec/changes/p01-…` à `p12-…`

## Licence

Apache-2.0
