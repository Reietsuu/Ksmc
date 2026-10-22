## MODIFIED Requirements

### Requirement: Services et console
Le dashboard SHALL lister les services avec filtres, afficher leur détail et proposer une console intégrée (logs en direct + saisie de commandes). Le détail d'un service SHALL afficher, quand ils sont connus, le TPS, le tas JVM utilisé/maximum et le temps de GC cumulé.

#### Scenario: Console web
- **WHEN** l'utilisateur tape `list` dans la console web de `lobby-1`
- **THEN** la sortie apparaît dans la console

#### Scenario: Mémoire d'un service
- **WHEN** l'utilisateur ouvre le détail de `lobby-1`, dont le bridge est actif
- **THEN** il voit le tas utilisé et maximum (ex. `612 Mo / 1 Go`) et le temps de GC
