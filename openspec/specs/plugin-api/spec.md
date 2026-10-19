# plugin-api Specification

## Purpose
Offrir aux plugins et mods tournant dans les services une API Java simple et stable pour
communiquer avec le cloud, quelle que soit la plateforme (Bukkit, Velocity, Bungee, Fabric, Forge).

## Requirements

### Requirement: Point d'entrée unique
Les plugins SHALL obtenir l'API par un point d'entrée statique identique sur toutes les plateformes, disponible dès l'activation du bridge.

#### Scenario: Plugin Bukkit
- **WHEN** un plugin dépendant de `ksmc-bridge` appelle le point d'entrée dans son activation
- **THEN** il obtient une instance utilisable avec le nom de son service

### Requirement: Informations du cloud
L'API SHALL fournir : le service courant, la liste et le détail des services (filtrables par task/environnement/état), les tasks, et les joueurs connectés.

#### Scenario: Lister les parties libres
- **WHEN** un plugin demande les services de `bedwars` à l'état `LOBBY`
- **THEN** il reçoit la liste correspondante avec joueurs et adresses

### Requirement: Communication
L'API SHALL permettre d'envoyer et recevoir des messages de channel, de faire des requêtes/réponses, de s'abonner
aux événements cloud et d'utiliser le stockage clé-valeur, avec des appels asynchrones ne bloquant pas le thread principal du serveur.

#### Scenario: Réponse asynchrone
- **WHEN** un plugin fait une requête sur un channel depuis le thread principal
- **THEN** l'appel retourne immédiatement un résultat futur, et le serveur ne gèle pas pendant l'attente

### Requirement: État du service
L'API SHALL permettre de définir l'état personnalisé, le motd et des propriétés libres du service courant.

#### Scenario: Propriété personnalisée
- **WHEN** un plugin définit la propriété `map=Desert`
- **THEN** `CloudService.status.properties.map` vaut `Desert`

### Requirement: Stabilité et distribution
L'API SHALL être publiée comme dépendance Maven versionnée sémantiquement, et les changements incompatibles SHALL n'arriver qu'avec une nouvelle version majeure.

#### Scenario: Dépendance Gradle
- **WHEN** un développeur ajoute `ksmc-plugin-api` en `compileOnly` depuis le dépôt Maven documenté
- **THEN** son projet compile contre l'API sans autre dépendance
