## MODIFIED Requirements

### Requirement: Santé
Le runtime SHALL exposer sur le port 8090 : `/live` (le process tourne) et `/ready` (le serveur répond à un
Server List Ping), sans nécessiter de plugin dans le serveur. Quand le bridge signale le service comme figé,
`/ready` SHALL répondre 503 même si le ping répond, et `/live` SHALL répondre 503 si le process ne s'est pas
arrêté dans le délai de drainage.

#### Scenario: Serveur en démarrage
- **WHEN** le monde est encore en génération
- **THEN** `/live` répond 200 et `/ready` répond 503

#### Scenario: Proxy figé
- **WHEN** le bridge a signalé `proxy-1` comme figé alors que son ping répond encore
- **THEN** `/ready` répond 503
