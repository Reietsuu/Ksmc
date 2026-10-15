# delivery-pipeline Specification

## Purpose
Garantir que chaque modification est compilée et testée automatiquement, et que les artefacts
(images, chart, CLI, plugins) sont publiés de façon reproductible.

## Requirements

### Requirement: Vérification de chaque push
Chaque push et chaque pull request SHALL déclencher la compilation, les tests unitaires et la
validation OpenSpec (`openspec validate --all`), et le statut SHALL être visible sur GitHub.

#### Scenario: Test cassé
- **WHEN** un commit fait échouer un test unitaire
- **THEN** le workflow GitHub Actions est rouge et indique le test en échec

### Requirement: Tests de bout en bout sur kind
Le pipeline SHALL exécuter une suite e2e sur un cluster kind éphémère, qui installe le chart construit
depuis le commit courant ; la même suite SHALL pouvoir être lancée en local par `scripts/e2e-kind.sh`.

#### Scenario: E2E local
- **WHEN** un développeur lance `scripts/e2e-kind.sh` sur une machine avec Docker
- **THEN** le script crée le cluster, installe Ksmc, exécute les scénarios et affiche un résumé succès/échec avec un code de sortie non nul en cas d'échec

### Requirement: Publication des artefacts
Un push sur `main` SHALL publier les images `ghcr.io/reietsuu/ksmc-controller` et
`ghcr.io/reietsuu/ksmc-runtime` taguées par SHA ; un tag `vX.Y.Z` SHALL créer une release GitHub
contenant le chart, la CLI et les JARs des bridges.

#### Scenario: Release
- **WHEN** le tag `v0.1.0` est poussé
- **THEN** une release GitHub `v0.1.0` existe avec les artefacts attachés et des images taguées `0.1.0`
