## ADDED Requirements

### Requirement: Détection d'un proxy figé
Le bridge d'un proxy SHALL vérifier, depuis un thread indépendant du proxy, que son planificateur et son bus
d'événements traitent encore des tâches. Sans réponse de l'un d'eux pendant `ksmc.watchdog.timeout-seconds`
(défaut 30), le proxy SHALL être déclaré figé. Le chien de garde SHALL être désactivable par
`ksmc.watchdog.enabled: false` et SHALL ignorer le démarrage du proxy.

#### Scenario: Plugin qui bloque les événements
- **WHEN** un plugin bloque le bus d'événements de `proxy-1` pendant 40 s
- **THEN** `proxy-1` est déclaré figé au bout de 30 s environ

#### Scenario: Charge passagère
- **WHEN** le planificateur de `proxy-1` répond avec 5 s de retard pendant un pic de connexions
- **THEN** `proxy-1` n'est pas déclaré figé

### Requirement: Sortie du proxy figé
Un proxy déclaré figé SHALL cesser d'être prêt (sonde `/ready` en échec) afin que le Service n'y envoie plus de
nouveaux joueurs, et le cloud SHALL publier l'événement `service.frozen`.

#### Scenario: Nouveaux joueurs
- **WHEN** `proxy-1` est déclaré figé et qu'un joueur se connecte via le Service de la task
- **THEN** le joueur arrive sur un autre proxy `Running`

### Requirement: Transfert des joueurs d'un proxy figé
Après la sortie du Service, le proxy figé SHALL tenter de transférer ses joueurs selon les mêmes règles qu'à
l'arrêt d'un proxy (clients 1.20.5+, autre proxy `Running` de la task, sinon message de repli), puis écrire l'état
de ses threads dans ses logs et arrêter son process pour que le service redémarre.

#### Scenario: Joueurs récents
- **WHEN** `proxy-1` est figé avec 3 joueurs 1.21 connectés et que `proxy-2` tourne
- **THEN** les 3 joueurs se retrouvent en jeu via `proxy-2`, puis `proxy-1` redémarre

#### Scenario: Réseau du proxy bloqué aussi
- **WHEN** le proxy figé ne peut plus rien envoyer à ses joueurs
- **THEN** le process est quand même arrêté au plus tard `ksmc.watchdog.drain-timeout-seconds` (défaut 10) après la détection
