## ADDED Requirements

### Requirement: Session unique par joueur
Un joueur (UUID) SHALL n'être connecté qu'à un seul proxy du namespace à la fois, toutes tasks proxy confondues.
La politique est choisie par task proxy via la propriété `ksmc.duplicate-login` : `deny` (défaut) refuse la
nouvelle connexion avec un message configurable (`ksmc.duplicate-login.message`) ; `replace` expulse l'ancienne
session puis accepte la nouvelle. Une session dont le proxy n'est plus `Running` ou n'a pas été rafraîchie dans le
délai de cohérence du registre SHALL être ignorée. Deux connexions simultanées du même UUID sur deux proxies SHALL
aboutir à une seule session acceptée.

#### Scenario: Double connexion refusée
- **WHEN** Steve est en jeu via `proxy-1` et qu'une connexion de Steve arrive sur `proxy-2` (politique `deny`)
- **THEN** la connexion sur `proxy-2` est refusée avec le message de double connexion et Steve reste sur `proxy-1`

#### Scenario: Ancienne session remplacée
- **WHEN** la task de `proxy-2` a la politique `replace` et que Steve, en jeu via `proxy-1`, se connecte sur `proxy-2`
- **THEN** Steve est expulsé de `proxy-1` puis accepté sur `proxy-2`, et le registre indique `proxy-2`

#### Scenario: Session orpheline
- **WHEN** `proxy-1` a été tué brutalement avec Steve connecté et que Steve se reconnecte sur `proxy-2`
- **THEN** la connexion est acceptée sans attendre l'expiration de l'ancienne entrée du registre

#### Scenario: Connexions simultanées
- **WHEN** deux connexions de Steve arrivent en même temps sur `proxy-1` et `proxy-2` (politique `deny`)
- **THEN** exactement une est acceptée

### Requirement: Commandes réseau
Le bridge proxy SHALL fournir des commandes qui agissent sur tout le réseau, quel que soit le proxy du joueur :
- `/glist [task]` : nombre total de joueurs et répartition par service (filtrable par task) ;
- `/find <joueur>` : proxy et serveur actuels du joueur ;
- `/send <joueur> <service|task>` : envoie le joueur vers un service, ou vers le service `Running` le moins rempli de la task ;
- `/alert <message>` : affiche le message à tous les joueurs de tous les proxies.

Chaque commande SHALL exiger la permission `ksmc.command.<nom>` ; elle SHALL être utilisable depuis la console du
proxy. Les commandes `/glist` et `/send` intégrées au proxy SHALL être remplacées sauf si la task définit
`ksmc.commands.replace-builtin: false` ; les commandes réseau restent alors disponibles sous `/ksmc <commande>`.

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

#### Scenario: Permission manquante
- **WHEN** un joueur sans `ksmc.command.send` tape `/send Steve lobby`
- **THEN** la commande est refusée et rien n'est envoyé

### Requirement: Transfert des joueurs à l'arrêt d'un proxy
Quand un proxy s'arrête (réduction de la task, mise à jour, arrêt manuel ou suppression du Pod), il SHALL, avant
de fermer les connexions, transférer chaque joueur vers un autre proxy `Running` de la même task :
- un client dont le protocole supporte le transfert (1.20.5+) SHALL recevoir un transfert vers l'adresse qu'il a
  utilisée pour se connecter, ou vers `ksmc.transfer.address` si la task la définit ;
- un client plus ancien, ou un joueur pour qui aucun autre proxy n'est `Running`, SHALL être déconnecté avec un
  message configurable (`ksmc.transfer.fallback-message`).

Les proxies SHALL accepter les connexions transférées. Le transfert SHALL être désactivable par task
(`ksmc.transfer.enabled: false`) et SHALL se terminer avant le délai d'arrêt du service.

#### Scenario: Réduction de la task proxy
- **WHEN** la task `proxy` passe de 2 à 1 service et que 4 joueurs 1.21 sont sur `proxy-2`
- **THEN** les 4 joueurs sont transférés et se retrouvent en jeu via `proxy-1` sans avoir à se reconnecter

#### Scenario: Client ancien
- **WHEN** `proxy-2` s'arrête avec un joueur en 1.8 connecté
- **THEN** ce joueur est déconnecté avec le message de repli

#### Scenario: Dernier proxy
- **WHEN** l'unique proxy de la task s'arrête
- **THEN** ses joueurs sont déconnectés avec le message de repli, sans tentative de transfert
