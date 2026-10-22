# multi-proxy Specification

## Purpose
Faire se comporter un réseau à plusieurs proxies comme un seul : une session par joueur, des commandes qui
voient tout le réseau et des arrêts de proxy sans coupure pour les clients récents.

## Requirements

### Requirement: Session unique par joueur
Un joueur (UUID) SHALL n'être connecté qu'à un seul proxy du namespace à la fois, toutes tasks proxy confondues.
Deux connexions simultanées du même UUID sur deux proxies SHALL aboutir à une seule session acceptée.

#### Scenario: Double connexion refusée
- **WHEN** Steve est en jeu via `proxy-1` et qu'une connexion de Steve arrive sur `proxy-2` (politique `deny`)
- **THEN** la connexion sur `proxy-2` est refusée avec le message de double connexion et Steve reste sur `proxy-1`

#### Scenario: Connexions simultanées
- **WHEN** deux connexions de Steve arrivent en même temps sur `proxy-1` et `proxy-2` (politique `deny`)
- **THEN** exactement une est acceptée

### Requirement: Pseudo unique sur le réseau
Un pseudo SHALL n'être connecté qu'une fois sur le réseau, sans tenir compte de la casse, même si les UUID diffèrent
(mode hors ligne, où l'UUID dépend de la casse exacte du pseudo). La politique de double connexion s'applique.
Cette garantie est séquentielle : seule l'unicité par UUID est garantie pour des connexions strictement simultanées.

#### Scenario: Casse différente en mode hors ligne
- **WHEN** `Steve` est en jeu via `proxy-1` et que `steve` se connecte via `proxy-2` (politique `deny`)
- **THEN** la connexion de `steve` est refusée avec le message de double connexion

### Requirement: Politique de double connexion
La politique SHALL être choisie par task proxy via la propriété `ksmc.duplicate-login` : `deny` (défaut) refuse la
nouvelle connexion avec le message `ksmc.duplicate-login.message` ; `replace` expulse l'ancienne session puis
accepte la nouvelle.

#### Scenario: Ancienne session remplacée
- **WHEN** la task de `proxy-2` a la politique `replace` et que Steve, en jeu via `proxy-1`, se connecte sur `proxy-2`
- **THEN** Steve est expulsé de `proxy-1` puis accepté sur `proxy-2`, et le registre indique `proxy-2`

### Requirement: Session orpheline
Une session dont le proxy n'est plus `Running`, ou qui n'a pas été rafraîchie dans le délai de cohérence du
registre, SHALL être ignorée par le contrôle de double connexion.

#### Scenario: Proxy tué
- **WHEN** `proxy-1` a été tué brutalement avec Steve connecté et que Steve se reconnecte sur `proxy-2`
- **THEN** la connexion est acceptée sans attendre l'expiration de l'ancienne entrée du registre

### Requirement: Commandes réseau
Le bridge proxy SHALL fournir `/glist [task]` (joueurs du réseau et répartition par service), `/find <joueur>`
(proxy et serveur actuels), `/send <joueur> <service|task>` (vers le service, ou le service `Running` le moins
rempli de la task) et `/alert <message>` (message à tous les joueurs de tous les proxies). Elles SHALL agir sur
tout le réseau, quel que soit le proxy du joueur.

#### Scenario: Liste globale
- **WHEN** 3 joueurs sont sur `proxy-1` et 2 sur `proxy-2` et qu'un administrateur tape `/glist` sur `proxy-1`
- **THEN** la réponse indique 5 joueurs et leur répartition par service

#### Scenario: Envoi d'un joueur d'un autre proxy
- **WHEN** un administrateur sur `proxy-1` tape `/send Steve bedwars` alors que Steve est connecté via `proxy-2`
- **THEN** Steve est envoyé sur le service `bedwars` `Running` le moins rempli

#### Scenario: Joueur introuvable
- **WHEN** un administrateur tape `/find Alex` et qu'Alex n'est connecté à aucun proxy
- **THEN** la commande répond qu'Alex n'est pas en ligne

#### Scenario: Annonce
- **WHEN** un administrateur tape `/alert Redémarrage dans 5 minutes`
- **THEN** tous les joueurs de tous les proxies du namespace reçoivent le message

### Requirement: Permissions et remplacement des commandes
Chaque commande réseau SHALL exiger la permission `ksmc.command.<nom>` et SHALL être utilisable depuis la console
du proxy. Les commandes `/glist` et `/send` intégrées au proxy SHALL être remplacées, sauf si la task définit
`ksmc.commands.replace-builtin: false` ; les commandes réseau restent alors disponibles sous `/ksmc <commande>`.

#### Scenario: Permission manquante
- **WHEN** un joueur sans `ksmc.command.send` tape `/send Steve lobby`
- **THEN** la commande est refusée et rien n'est envoyé

### Requirement: Transfert des joueurs à l'arrêt d'un proxy
Quand un proxy s'arrête (réduction de la task, mise à jour, arrêt manuel ou suppression du Pod), il SHALL, avant de
fermer les connexions, transférer chaque client 1.20.5+ vers un autre proxy `Running` de la même task, à l'adresse
que le client a utilisée ou à `ksmc.transfer.address` si la task la définit. Les proxies SHALL accepter les
connexions transférées, et le transfert SHALL se terminer avant le délai d'arrêt du service.

#### Scenario: Réduction de la task proxy
- **WHEN** la task `proxy` passe de 2 à 1 service et que 4 joueurs 1.21 sont sur `proxy-2`
- **THEN** les 4 joueurs sont transférés et se retrouvent en jeu via `proxy-1` sans avoir à se reconnecter

### Requirement: Repli quand le transfert est impossible
Un client antérieur à 1.20.5, un joueur pour qui aucun autre proxy n'est `Running`, ou tout joueur d'une task qui
définit `ksmc.transfer.enabled: false`, SHALL être déconnecté avec le message `ksmc.transfer.fallback-message`.

#### Scenario: Client ancien
- **WHEN** `proxy-2` s'arrête avec un joueur en 1.8 connecté
- **THEN** ce joueur est déconnecté avec le message de repli

#### Scenario: Dernier proxy
- **WHEN** l'unique proxy de la task s'arrête
- **THEN** ses joueurs sont déconnectés avec le message de repli, sans tentative de transfert
