# Design

## Decisions

1. **Joueur bot** : client minimal écrit dans `ksmc-e2e` (`BotClient`, protocole 1.8 n° 47, hors ligne,
   compression, keep-alive) plutôt qu'une bibliothèque (MCProtocolLib) : aucune dépendance, et le protocole 1.8 ne
   change plus. Conséquence : le bot atterrit sur la task Spigot 1.8.8 (fallback prioritaire) et change de serveur
   entre deux services legacy ; les serveurs Paper latest, Fabric et NeoForge sont vérifiés par leur enregistrement
   dans les proxies et le rapport de leur bridge. Tout le réseau de recette utilise `bridge.forwarding: legacy`
   (Spigot 1.8.8 ne connaît pas le forwarding moderne).
2. **Image de build** : BuildTools compile Spigot (JDK + git requis). `ArtifactPlan.BuildJob` gagne un champ `jdk`
   (constructeur à 5 arguments conservé, compatibilité des modules) ; ces Jobs utilisent l'image
   `ksmc-runtime:<tag>-java<N>-jdk` (`JAVA_FLAVOR=jdk` du Dockerfile runtime, publiée par le workflow images).
   Défaut révélé par la recette : les Jobs BuildTools tournaient sur l'image JRE.
3. **Montée de version** : `scripts/e2e-upgrade.sh` (job CI `upgrade`, cluster kind séparé) installe la version
   précédente — dernier tag `v*`, sinon `origin/main` — avec ses images publiées sur ghcr.io (`sha-<commit>` ou la
   version), crée un service statique avec un fichier, met à jour vers le code courant et vérifie uid, volumes,
   fichier et une fonctionnalité nouvelle (`/metrics`).
4. **TESTING.md vérifié** : les blocs ```` ```bash test ```` de `docs/TESTING.md` sont exécutés par
   `scripts/testing-md.sh` à la fin du job e2e (même cluster kind) ; les étapes interactives (dashboard, client
   Minecraft, module) restent manuelles et sont couvertes par les scénarios e2e.
5. **1.3 (e2e local)** : non exécuté par l'agent — consigne du propriétaire après la saturation de la machine de
   développement le 2026-10-19 (e2e uniquement sur GitHub Actions). Le script local est identique à celui de la CI ;
   à lancer par le propriétaire s'il le souhaite sur une machine dédiée.
6. **Release** : le tag `v0.1.0` est posé sur `main` après merge des PR #10 à #12 (merge réservé au propriétaire) ;
   le workflow `release.yml` publie images, chart, CLI, bridges et artefacts Maven.
