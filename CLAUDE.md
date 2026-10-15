# Ksmc — instructions pour l'agent

Projet : équivalent CloudNet sur Kubernetes. Lire `docs/ARCHITECTURE.md` pour la vue d'ensemble.
Le développement suit OpenSpec : `openspec/changes/pNN-*` (ordre = numéro).

## Protocole de session (quota Claude Pro : fenêtres de 5 h et de 7 jours)

Le quota peut couper une session à tout moment. Le dépôt doit donc toujours être reprenable.

1. **Reprise** : `openspec list` → prendre le premier change `pNN` non terminé →
   lire seulement son `tasks.md` + les sections utiles de `design.md`/specs. Ne pas relire tout le dépôt.
   Lire `docs/HANDOFF.md` s'il existe (état en cours, pièges rencontrés).
2. **Travail** : une tâche à la fois. Cocher la case dans `tasks.md` dans le même commit que le code.
3. **Commits** : auteur = Reietsuu (config git locale du dépôt, e-mail noreply GitHub ; vérifier
   `git config user.name` avant le premier commit d'une session, jamais `root`). Conventional Commits, push sur `main` à la fin de chaque groupe de tâches
   (ou plus tôt si le travail est gros). Jamais de travail non commité en fin de session.
4. **Économie de tokens** :
   - lire les fichiers par plages (`sed -n`, `grep -n`) plutôt qu'en entier ;
   - lancer les builds avec `-q` et ne regarder que les erreurs (`| tail -40`) ;
   - réutiliser les patterns existants, pas de refactor non demandé ;
   - les tests lourds (e2e kind) passent par GitHub Actions : pousser, puis `gh run watch`.
5. **Fin de session ou quota bas** : mettre à jour `docs/HANDOFF.md` (5-10 lignes : tâche en cours,
   ce qui reste, problèmes ouverts), commit + push.
6. **Questions** : les specs ont été validées au début ; ne poser une question que si une décision
   change le comportement externe. Sinon choisir le défaut raisonnable et le noter dans `design.md`.

## Commandes

- Build + tests unitaires : `./gradlew build -q`
- Dashboard : `cd dashboard && npm ci && npm run build`
- Valider les specs : `openspec validate --all`
- E2E local : `./scripts/e2e-kind.sh` (crée un cluster kind, installe le chart, lance les scénarios)
- CI : `gh run list -L 5`, `gh run watch`

## Règles de code

- Java 21 pour `ksmc-api`, `ksmc-controller`, `ksmc-cli` ; `--release 8` pour wire/wrapper/plugin-api/bridges legacy.
- Pas de dépendance nouvelle sans la justifier dans le `design.md` du change.
- Toute nouvelle variation de comportement passe par une SPI de `ksmc-api` (voir ARCHITECTURE.md).
