# Feuille de route

La v0.1 couvre le cœur d'un cloud Minecraft sur Kubernetes (voir le [README](../README.md)). Les pistes suivantes
sont prévues ou ouvertes aux contributions ; la plupart s'appuient sur les points d'extension existants (SPI de
`ksmc-api`, modules, messagerie) plutôt que sur des changements du cœur.

## Fonctionnalités de jeu (modules)

| Piste | Idée | Point d'extension |
|---|---|---|
| Permissions | Groupes et permissions des joueurs synchronisés sur tout le réseau (équivalent CloudPerms) | module + messagerie KV, plugin |
| Panneaux de connexion | Signs/NPC qui affichent l'état des services et y envoient les joueurs | plugin + API des services |
| Smart / labymod / tablist | Modules d'intégration client et d'affichage réseau | modules |
| Report / sanctions | Bannissements et mutes globaux, historique | module + routes REST + page dashboard |
| Mise en file d'attente | File d'attente avant un serveur plein | bridge proxy |

## Plateforme

| Piste | Idée | Point d'extension |
|---|---|---|
| OIDC / SSO | Connexion au dashboard et à l'API par un fournisseur d'identité | `ApiAuthenticator` |
| Stockage S3 | Template Store et artefacts sur S3/MinIO au lieu du volume du controller | `ArtifactStorage` |
| Multi-cluster | Plusieurs clusters pour un même réseau (proxies et serveurs répartis) | messagerie NATS (leaf nodes), registre |
| Haute disponibilité | Plusieurs réplicas du controller (élection de leader, stockage partagé) | JOSDK leader election |
| Images natives | Controller et CLI compilés avec GraalVM (démarrage rapide, mémoire réduite) | build |
| Permissions NATS par service | Un utilisateur NATS par service (aujourd'hui partagé) pour isoler les rapports | controller + chart |
| Traçage distribué | OpenTelemetry pour les réconciliations et l'API | observabilité |
| Sauvegardes | Sauvegarde planifiée des services statiques (snapshots de volumes) | CRD + CronJob |
| Autres logiciels | Purpur, Pufferfish, Sponge, Geyser/Floodgate | `SoftwareResolver` |
| Autoscaling avancé | Stratégies prédictives, métriques externes (KEDA) | `ScalingStrategy` |

## Qualité

- Tests de charge (centaines de services, milliers de joueurs bots).
- Montée de version testée entre toutes les versions mineures (aujourd'hui : version précédente → courante).
- Traductions du dashboard et de la CLI (anglais).

Les idées et retours sont bienvenus dans les issues GitHub.
