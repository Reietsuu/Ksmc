# Design

## Context

Dépôt vide. Machine de dev : JDK 25, Docker, Node 20, `gh` authentifié (compte Reietsuu), pas de Gradle,
kubectl, kind ni helm installés (à télécharger dans `./.tools/` par les scripts, non commités).
Vue d'ensemble : `docs/ARCHITECTURE.md`.

## Goals / Non-Goals

**Goals :** build reproductible, CRDs générées depuis le code Java (une seule source de vérité),
chart installable, CI complète dès le premier change pour que chaque change suivant soit vérifié.

**Non-Goals :** réconciliation métier, haute disponibilité du controller (élection de leader prévue
dès maintenant dans le code mais un seul replica par défaut).

## Decisions

1. **Gradle 9 + wrapper commité, Kotlin DSL, convention plugins dans `build-logic/`.**
   Deux conventions : `ksmc.java21` et `ksmc.java8` (`options.release`). Pas de toolchain auto-provisionnée :
   le JDK 21+ installé compile les deux (`--release`). Alternative Maven écartée (multi-release + Fabric Loom plus simples avec Gradle).
2. **CRDs générées depuis les POJOs de `ksmc-api`** (fabric8 `crd-generator-api-v2`, exécuté par une tâche
   Gradle `generateCrds`) vers `deploy/helm/ksmc/crds/`. La CI échoue si les fichiers générés diffèrent
   du commit. Alternative (YAML écrit à la main + génération Java) écartée : double maintenance.
3. **Controller = Java Operator SDK 5 + fabric8 7 + Javalin 6** pour HTTP (santé maintenant, REST en p08).
   Quarkus écarté : builds plus lourds et magie qui coûte cher à déboguer avec un quota limité.
4. **Images par Dockerfile multi-stage** (`docker/controller.Dockerfile`, `docker/runtime.Dockerfile`),
   base `eclipse-temurin:<v>-jre`. La runtime a une variante par Java (8, 17, 21, 25) — utilisée en p02.
5. **NATS** via dépendance du chart officiel `nats` (JetStream activé, 1 replica par défaut), désactivable.
6. **E2E** : projet Gradle `ksmc-e2e` (JUnit 5 + fabric8 client) exécuté contre le kubeconfig courant,
   orchestré par `scripts/e2e-kind.sh` (kind + helm + build/charge des images locales). Même script en CI.
7. **Champs `extensions`** : `Map<String, JsonNode>` avec `x-kubernetes-preserve-unknown-fields`.
8. **Licence** : Apache-2.0 (comme CloudNet), modifiable avant la première release.

## Risks / Trade-offs

- [crd-generator ne couvre pas toutes les validations (CEL)] → compléter par annotations
  `@ValidationRule` et tests qui appliquent des ressources invalides sur kind.
- [Images multi-Java longues à construire en CI] → cache GHA buildx, matrice parallèle.
- [Quota agent] → la CI e2e tourne sur GitHub ; en local seulement si nécessaire.

## Migration Plan

Premier livrable, pas de migration. Les CRDs sont `v1alpha1` : changements cassants autorisés jusqu'à `v1beta1`.
