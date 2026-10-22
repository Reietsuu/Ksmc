## MODIFIED Requirements

### Requirement: Informations du cloud
L'API SHALL fournir : le service courant, la liste et le détail des services (filtrables par task/environnement/état), les tasks, et les joueurs connectés. Le détail d'un service SHALL inclure, quand le bridge les remonte, le TPS, le tas JVM utilisé et maximum, et le nombre et la durée cumulée des GC.

#### Scenario: Lister les parties libres
- **WHEN** un plugin demande les services de `bedwars` à l'état `LOBBY`
- **THEN** il reçoit la liste correspondante avec joueurs et adresses

#### Scenario: Mémoire d'un autre serveur
- **WHEN** un plugin demande le service `lobby-1`, dont le bridge est actif
- **THEN** il reçoit son tas utilisé et maximum en octets

## ADDED Requirements

### Requirement: Métriques de plugins
L'API SHALL permettre à un plugin de déclarer des compteurs, des jauges (lues à chaque envoi) et des timers, avec
des étiquettes clé/valeur. Les noms SHALL respecter `[a-z][a-z0-9_]*` ; un nom invalide SHALL être refusé à la
déclaration par une exception explicite. Un plugin SHALL pouvoir utiliser ces métriques même si le cloud est
injoignable (elles sont alors envoyées dès la reconnexion).

#### Scenario: Compteur de parties
- **WHEN** le plugin `bedwars` déclare le compteur `games` et l'incrémente 3 fois
- **THEN** la valeur 3 est disponible pour l'exposition du service

#### Scenario: Nom invalide
- **WHEN** un plugin déclare la métrique `Games-Played`
- **THEN** la déclaration échoue avec un message indiquant le format attendu
