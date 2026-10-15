# Spec Delta

## Purpose

Donner, comme l'écran de CloudNet, accès en direct à la console de chaque service depuis le
dashboard, la CLI ou l'API.

## ADDED Requirements

### Requirement: Logs en direct
Un client autorisé SHALL pouvoir ouvrir la console d'un service et recevoir les N dernières lignes (défaut 200) puis les nouvelles lignes en temps réel.

#### Scenario: Ouverture de console
- **WHEN** un utilisateur ouvre la console de `lobby-1`
- **THEN** il voit les 200 dernières lignes puis chaque nouvelle ligne en moins d'1 s

### Requirement: Commandes interactives
Depuis la console, un client avec `ksmc.service.command` SHALL pouvoir envoyer des commandes au service.

#### Scenario: Commande depuis la console
- **WHEN** l'utilisateur tape `list` dans la console
- **THEN** la sortie de la commande apparaît dans la console

### Requirement: Logs des services terminés
Les logs du dernier démarrage d'un service arrêté SHALL rester consultables tant que son Pod existe, et le dernier extrait (500 lignes) d'un service `Failed` SHALL être conservé dans son statut ou un ConfigMap associé.

#### Scenario: Crash
- **WHEN** un service passe en `Failed`
- **THEN** l'utilisateur peut lire les dernières lignes ayant précédé l'échec
