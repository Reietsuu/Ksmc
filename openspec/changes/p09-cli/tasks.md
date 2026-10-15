# Tasks

## 1. Socle

- [ ] 1.1 Projet `ksmc-cli` : picocli, contextes `~/.ksmc/config.yaml` (permissions 600), login, client REST, formats de sortie, codes de sortie ; tests unitaires
- [ ] 1.2 Commandes task/group/template/service/player/module/user/token/events ; tests contre un serveur HTTP de test

## 2. Interactif et extensibilité

- [ ] 2.1 Console interactive (WebSocket + JLine), `logs -f` ; test avec serveur WebSocket de test
- [ ] 2.2 Commandes de modules dynamiques + complétion shell ; tests unitaires
- [ ] 2.3 Packaging (zip + image), ajout à `release.yml` ; scénario e2e CLI (login, task start, service list -o name, commande du module hello) ; doc `docs/CLI.md` ; vérifier en CI
