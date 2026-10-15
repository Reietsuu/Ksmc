# Architecture de Ksmc

Ksmc (Kubernetes Server Minecraft Cloud) reprend les concepts de CloudNet (tasks, groupes,
templates, services statiques/dynamiques, bridge, modules, messagerie par channels) en les
traduisant en primitives Kubernetes.

## Correspondance CloudNet → Ksmc

| CloudNet                      | Ksmc                                                         |
|-------------------------------|--------------------------------------------------------------|
| Node                          | `ksmc-controller` (Deployment) — opérateur + REST + modules  |
| Wrapper                       | `ksmc-wrapper` (process PID 1 du conteneur de service)       |
| ServiceTask                   | CR `ServiceTask`                                             |
| GroupConfiguration            | CR `ServiceGroup`                                            |
| ServiceTemplate / storage     | CR `ServiceTemplate` + Template Store (PVC du controller)     |
| ServiceInfoSnapshot           | CR `CloudService` (`status`)                                 |
| ServiceVersion (software)     | CR `SoftwareDefinition`                                      |
| Module                        | CR `KsmcModule` + JAR chargé par le controller               |
| Channel messages              | NATS, sujets `ksmc.<ns>.channel.<channel>`                   |
| Bridge                        | `ksmc-bridge-*` (plugins Velocity/Bungee/Paper/Spigot/Fabric/Forge) |
| SmartModule (autoscale)       | Stratégie de scaling intégrée (SPI `ScalingStrategy`)        |
| Console / screen              | WebSocket controller ↔ logs du Pod + stdin via wrapper        |

## Composants

```
            kubectl ──┐         ksmc CLI ──┐      Dashboard (navigateur)
                      │                    │              │
                      ▼                    ▼              ▼
              ┌─────────────── API Kubernetes ◄──── ksmc-controller ───────────────┐
              │  CRDs ksmc.io/v1alpha1          │  • reconcilers (JOSDK)           │
              │                                 │  • API REST /api/v1 + WebSocket  │
              │                                 │  • hôte de modules (SPI)         │
              │                                 │  • Template Store (PVC)          │
              │                                 └───────────┬──────────────────────┘
              ▼                                             │ NATS (channels, events, RPC, KV)
      Pods CloudService ────────────────────────────────────┘
      [initContainer: ksmc-wrapper prepare]  → logiciel + templates + config
      [container: ksmc-wrapper run]          → lance le serveur, santé, stdin, métriques
         └─ plugin/mod ksmc-bridge-*         → enregistrement proxy, joueurs, messagerie
```

### Modules Gradle

| Projet                    | Bytecode | Rôle                                                     |
|---------------------------|----------|----------------------------------------------------------|
| `ksmc-api`                | 21       | Modèle CRD, SPI des modules, API événements/messagerie   |
| `ksmc-controller`         | 21       | Opérateur, REST, hôte de modules, template store         |
| `ksmc-wire`               | 8        | Protocole JSON partagé (messages, events, RPC)           |
| `ksmc-messaging-nats`     | 8        | Transport NATS de `ksmc-wire`                            |
| `ksmc-wrapper`            | 8        | Préparation + supervision du process serveur             |
| `ksmc-plugin-api`         | 8        | API publique pour plugins (channels, infos services, joueurs) |
| `ksmc-bridge-velocity`    | 17/21    | Plugin Velocity                                           |
| `ksmc-bridge-bungee`      | 8        | Plugin BungeeCord/Waterfall                               |
| `ksmc-bridge-bukkit`      | 8        | Plugin Spigot/Paper                                       |
| `ksmc-bridge-fabric`      | 21       | Mod Fabric (ligne MC récente)                             |
| `ksmc-bridge-forge`       | 21       | Mod NeoForge (ligne MC récente) — change dédié            |
| `ksmc-cli`                | 21       | CLI `ksmc` (picocli) sur l'API REST                       |
| `dashboard/`              | —        | React + TS + Vite, embarqué dans l'image du controller   |

## Points d'extension (SPI)

Toute variation passe par une interface de `ksmc-api` que les modules peuvent fournir :

- `SoftwareResolver` : résoudre un type de logiciel → artefacts téléchargeables.
- `TemplateSourceProvider` : nouvelles sources de templates (ex. S3).
- `ServicePodCustomizer` : modifier la spec du Pod avant création.
- `ScalingStrategy` : décider du nombre de services d'une task.
- `MessagingTransport` : remplacer NATS (ex. Redis).
- `RestExtension` / `DashboardExtension` / `CliCommandDescriptor` : étendre les interfaces.
- `EventListener` : réagir aux événements cloud (service démarré, joueur connecté...).

Côté serveurs, `ksmc-plugin-api` donne aux plugins un accès simple aux channels, aux infos
de services et aux joueurs — c'est la « communication simple » entre services et modules.

## Conventions

- Groupe API `ksmc.io`, version `v1alpha1`. Noms de ressources courts : `mctask`, `mcgroup`,
  `mctpl`, `mcsvc`, `mcsw`, `mcmod`.
- Labels posés sur chaque objet géré : `ksmc.io/task`, `ksmc.io/service`, `ksmc.io/environment`
  (`proxy` | `server`), `app.kubernetes.io/managed-by=ksmc`.
- Sujets NATS : `ksmc.<namespace>.<domaine>.<...>` (voir spec `messaging`).
- REST : `/api/v1/...`, OpenAPI publiée sur `/api/v1/openapi.json`.
- Tout le texte utilisateur (docs, specs) en français ; le code et les identifiants en anglais.
