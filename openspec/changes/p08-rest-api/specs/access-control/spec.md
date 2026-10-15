# Spec Delta

## Purpose

Contrôler qui peut faire quoi via l'API, la CLI et le dashboard, avec des comptes, rôles et
permissions fines que les modules peuvent étendre.

## ADDED Requirements

### Requirement: Compte administrateur initial
L'installation SHALL créer un compte `admin` dont le mot de passe aléatoire est stocké dans un Secret Kubernetes, à changer à la première connexion.

#### Scenario: Première connexion
- **WHEN** l'opérateur lit le Secret `ksmc-admin` et se connecte avec ce mot de passe
- **THEN** la connexion réussit et l'API exige un changement de mot de passe avant toute autre action

### Requirement: Authentification
L'API SHALL accepter une session obtenue par nom/mot de passe (jeton à durée limitée renouvelable) et des jetons d'API
nommés, révocables, avec date d'expiration optionnelle ; toute requête non authentifiée hors santé, login et OpenAPI SHALL recevoir 401.

#### Scenario: Jeton révoqué
- **WHEN** un jeton d'API est révoqué
- **THEN** la requête suivante avec ce jeton reçoit 401

### Requirement: Rôles et permissions
Les droits SHALL être des permissions nommées (`ksmc.task.read`, `ksmc.service.command`, joker `*` accepté) groupées en
rôles ; les rôles par défaut SHALL être `admin`, `operator` et `viewer`, et les modules SHALL pouvoir déclarer leurs permissions.

#### Scenario: Viewer en écriture
- **WHEN** un utilisateur `viewer` tente `DELETE /api/v1/services/lobby-1`
- **THEN** la réponse est 403 avec la permission manquante

### Requirement: Protection des secrets
Les mots de passe SHALL être stockés hachés (argon2id ou bcrypt), les jetons SHALL n'être affichés qu'à leur création, et les tentatives de connexion SHALL être limitées en débit.

#### Scenario: Force brute
- **WHEN** 10 tentatives échouent en une minute pour un compte
- **THEN** les tentatives suivantes reçoivent 429 pendant au moins une minute

### Requirement: Audit
Chaque action d'écriture SHALL être journalisée avec l'utilisateur, l'action, la cible, le résultat et l'heure, consultable via l'API.

#### Scenario: Qui a arrêté le service
- **WHEN** l'admin consulte l'audit après l'arrêt de `lobby-2`
- **THEN** il voit l'utilisateur et l'heure de l'arrêt
