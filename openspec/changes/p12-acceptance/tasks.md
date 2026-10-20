# Tasks

## 1. Suite complète

- [x] 1.1 Scénario e2e « réseau complet » (Velocity + Bungee, Paper, Spigot 1.8.8, Fabric, NeoForge, joueur bot traversant le réseau) ; vérifier en CI
- [ ] 1.2 Scénario de mise à jour du chart (version précédente → courante) sans perte des services statiques ; vérifier en CI
- [ ] 1.3 Exécuter `scripts/e2e-kind.sh` en local une fois et corriger les écarts avec la CI ; vérifier code de sortie 0

## 2. Livraison

- [ ] 2.1 `docs/TESTING.md` : ordres de test manuels (installation, chaque capacité, nettoyage) avec résultats attendus ; vérifier chaque commande sur kind
- [ ] 2.2 `docs/ROADMAP.md` et mise à jour du `README.md` (fonctionnalités, captures, liens) ; vérifier les liens
- [ ] 2.3 Tag `v0.1.0` et vérification de la release (images, chart, CLI, bridges, artefacts Maven) ; vérifier `gh release view v0.1.0`

## Workflow follow-up

- Archiver tous les changes p01–p12 et vérifier `openspec list --specs`.
