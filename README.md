# Ksmc — Kubernetes Server Minecraft Cloud

Ksmc est un cloud Minecraft dans l'esprit de [CloudNet](https://github.com/CloudNetService/CloudNet), construit
nativement sur Kubernetes : tasks, groupes, templates, services statiques/dynamiques, bridge proxy/serveurs,
messagerie entre services, modules — avec toute la puissance de Kubernetes (scheduling, volumes, affinités,
autoscaling, observabilité).

Spécifications et historique de conception : [OpenSpec](https://github.com/Fission-AI/OpenSpec), voir
`openspec/specs/` (capacités) et `openspec/changes/archive/` (changes p01 à p12).

![Dashboard](docs/images/dashboard/overview.png)

## Fonctionnalités (v0.1)

- **Logiciels** : Paper, Folia, Spigot/Bukkit (BuildTools), Velocity, BungeeCord, Waterfall, Fabric, Forge, NeoForge ;
  versions résolues automatiquement, Java choisi selon la version de Minecraft (8 à 25).
- **Gestion** par **kubectl** (CRDs `ksmc.io`), **API REST** (OpenAPI), **CLI `ksmc`** et **dashboard web** temps réel,
  toutes cohérentes entre elles, avec comptes, rôles, jetons et audit.
- **Tasks et groupes** : services dynamiques ou statiques persistants, mises à jour progressives, maintenance,
  détection des crashs en boucle.
- **Templates** : stockage intégré (éditable dans le dashboard), git, http, ConfigMap, image OCI.
- **Bridge** : enregistrement dynamique des serveurs dans les proxies, fallback, forced hosts, registre des joueurs,
  actions (envoi, message, expulsion), console des services.
- **Messagerie** (NATS) : channels, requêtes/réponses, événements, clé-valeur, pour plugins et modules.
- **Modules** (SPI) : routes REST, commandes CLI, pages du dashboard, métriques, résolveurs, sources de templates…
- **Autoscaling** par remplissage et par horaire, services préparés à l'avance.
- **Observabilité** : métriques Prometheus (controller et services : joueurs, TPS, JVM), alertes, tableau de bord
  Grafana, logs JSON.

## Démarrage rapide

```bash
helm dependency update deploy/helm/ksmc
helm install ksmc deploy/helm/ksmc -n ksmc --create-namespace --set minecraft.acceptEula=true
kubectl -n ksmc apply -f examples/02-group.yaml -f examples/03-template.yaml -f examples/04-tasks.yaml
kubectl -n ksmc get mctask,mcsvc                 # services en cours de démarrage
kubectl -n ksmc port-forward svc/ksmc-controller 8080   # dashboard : http://localhost:8080
```

Détails dans [INSTALL.md](docs/INSTALL.md) ; procédure de vérification complète dans [TESTING.md](docs/TESTING.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Installation](docs/INSTALL.md) · [Utilisation](docs/USAGE.md)
- [API REST](docs/API.md) · [CLI](docs/CLI.md) · [Messagerie](docs/MESSAGING.md) · [Modules et plugins](docs/MODULES.md) · [Dashboard](docs/DASHBOARD.md) · [Observabilité](docs/OBSERVABILITY.md)
- [Tests manuels](docs/TESTING.md) · [Feuille de route](docs/ROADMAP.md)

## Licence

Apache-2.0
