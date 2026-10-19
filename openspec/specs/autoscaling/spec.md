# autoscaling Specification

## Purpose
Ajuster automatiquement le nombre de services d'une task à la charge réelle en joueurs et aux
horaires, dans des bornes configurées.

## Requirements

### Requirement: Scaling par remplissage
Avec la stratégie `players`, le controller SHALL démarrer un nouveau service quand le remplissage moyen des services
`Running` de la task dépasse `scaleUpThreshold` (défaut 0.8), dans la limite de `maxServiceCount`.

#### Scenario: Montée en charge
- **WHEN** 2 services de 20 places ont 17 joueurs chacun avec un seuil à 0.8
- **THEN** un troisième service est démarré

### Requirement: Arrêt des services vides
Un service sans joueur depuis `idleStopSeconds` (défaut 300) SHALL être arrêté si le nombre de services reste supérieur ou égal au minimum.

#### Scenario: Service vide
- **WHEN** `game-3` est vide depuis 6 minutes et que la task a 3 services pour un minimum de 2
- **THEN** `game-3` est arrêté

### Requirement: Anti-oscillation
Après une décision de scaling, aucune décision inverse SHALL être prise avant `cooldownSeconds` (défaut 60), et un seul
démarrage SHALL être en cours à la fois par task, sauf si `maxConcurrentStarts` l'autorise.

#### Scenario: Pic bref
- **WHEN** le remplissage dépasse le seuil puis redescend 10 s plus tard
- **THEN** au plus un service est démarré et aucun arrêt n'a lieu avant la fin du cooldown

### Requirement: Services préparés
Une task SHALL pouvoir maintenir `preparedServices` services démarrés mais exclus du routage des joueurs, promus
automatiquement quand le scaling demande un nouveau service.

#### Scenario: Promotion
- **WHEN** le scaling demande un service et qu'un service préparé est `Running`
- **THEN** ce service devient routable immédiatement et un nouveau service préparé est démarré

### Requirement: Minimum programmé
Avec la stratégie `schedule`, le minimum SHALL varier selon des règles cron avec fuseau horaire, combinable avec la stratégie `players`.

#### Scenario: Soirée
- **WHEN** une règle fixe le minimum à 5 de 18h à 23h Europe/Paris
- **THEN** à 18h la task monte à 5 services au moins et redescend au minimum de base après 23h

### Requirement: Transparence des décisions
Chaque décision SHALL produire un événement Kubernetes et un événement cloud `task.scaled` avec l'ancienne et la nouvelle cible et la raison.

#### Scenario: Audit
- **WHEN** un service est démarré par l'autoscaling
- **THEN** `kubectl describe mctask game` montre un événement `ScaledUp` avec le taux de remplissage observé
