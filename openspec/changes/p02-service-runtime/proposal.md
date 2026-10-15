# Proposal

## Why

Le cœur de CloudNet est de démarrer une instance Minecraft prête à jouer à partir d'un logiciel et
de templates. Ksmc doit faire la même chose avec un Pod : un `CloudService` déclaré doit devenir un
serveur Paper, Spigot, Velocity, BungeeCord/Waterfall, Fabric ou Forge fonctionnel, quel que soit le logiciel.

## What Changes

- Catalogue de logiciels déclaratif (`SoftwareDefinition`) avec des résolveurs intégrés :
  PaperMC (Paper, Folia, Velocity, Waterfall), BungeeCord (Jenkins), Spigot (BuildTools avec cache),
  Fabric (meta API + installation optionnelle de FabricProxy-Lite), Forge et NeoForge (installeur), URL directe.
- Définitions par défaut installées par le chart.
- Templates : sources `storage` (Template Store intégré au controller), `git`, `http`, `configMap`, `image` ;
  ordre d'application défini ; inclusion conditionnelle par environnement.
- `ksmc-wrapper` : préparation (logiciel, templates, configuration réseau) puis supervision du process
  (santé par Server List Ping, arrêt gracieux, commandes stdin).
- Images runtime `ksmc-runtime:java8|java17|java21|java25` et choix automatique selon la version Minecraft.
- Réconciliation `CloudService` → Pod (+ PVC si statique), phases et conditions dans `status`.

**Hors périmètre** : création des services par les tasks (p03), enregistrement auprès des proxies (p05).
**Laissé ouvert** : nouveaux résolveurs et nouvelles sources de templates via SPI (modules, p06).

## Capabilities

### New Capabilities
- `software-catalog` : description et résolution des logiciels serveur/proxy téléchargeables.
- `service-templates` : sources de fichiers appliquées aux services et stockage de templates.
- `service-runtime` : comportement du conteneur d'un service (préparation, lancement, santé, arrêt).
- `service-lifecycle` : cycle de vie d'un `CloudService` et de ses ressources Kubernetes.

### Modified Capabilities
- (aucune)

## Impact

Nouveaux projets `ksmc-wrapper` ; reconcilers `CloudService`, `SoftwareDefinition`, `ServiceTemplate` dans
`ksmc-controller` ; Template Store (PVC + endpoints internes) ; `docker/runtime.Dockerfile` ; chart
(PVC template store, SoftwareDefinitions par défaut).
