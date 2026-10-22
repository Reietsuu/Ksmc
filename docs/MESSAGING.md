# Messagerie Ksmc

La messagerie relie le controller, ses modules, les wrappers et les plugins des serveurs/proxies.
Transport par défaut : **NATS** (JetStream pour les événements et le stockage clé-valeur), derrière
l'interface `MessagingTransport` de `ksmc-wire` (remplaçable par un module).

## Concepts

| Concept | Usage | Garantie |
|---|---|---|
| **Channel** | messages applicatifs (`chat`, `party`, `shop`...) avec ciblage | au plus une fois |
| **Requête/réponse** | question à un ou plusieurs composants, première réponse gagnante | timeout (défaut 5 s) |
| **Événements cloud** | ce qui se passe (`service.ready`, `player.login`, `module.x.y`) | conservés 1 h, rejouables |
| **Clé-valeur** | état partagé (compteurs, files, sessions) | révisions, comparer-et-écrire, TTL |
| **Commandes console** | controller → wrapper d'un service (stdin du serveur) | accusé de réception |

Tout est **isolé par namespace Kubernetes**.

## Ciblage

| Cible | Reçu par |
|---|---|
| `all` | tous les composants abonnés au channel |
| `service:<nom>` | ce service |
| `task:<nom>` | les services de cette task |
| `environment:proxy` / `environment:server` | tous les proxies / tous les serveurs |
| `controller` | le controller et ses modules |

## Sujets NATS

| Sujet | Contenu |
|---|---|
| `ksmc.<ns>.ch.<channel>.all` | message de channel pour tous |
| `ksmc.<ns>.ch.<channel>.service.<nom>` / `.task.<nom>` / `.env.<env>` / `.controller` | message ciblé |
| `ksmc.<ns>.ev.<type>` | événement (stream JetStream `KSMC_EVENTS_<NS>`) |
| `ksmc.<ns>.cmd.<service>` | commande console (requête, réponse `ok` ou `error: ...`) |
| bucket `ksmc_<ns>_<nom>` | stockage clé-valeur (bucket `shared` créé par défaut) |

Noms de channels, tasks et services : lettres, chiffres, `_` et `-`.

## Format (JSON, version 1)

Message de channel :

```json
{"v": 1, "id": "…", "channel": "chat", "message": "broadcast",
 "data": {"text": "Bonjour"}, "sender": {"type": "SERVICE", "name": "lobby-1"},
 "target": {"type": "TASK", "name": "bedwars"}, "timestamp": 1760000000000}
```

Événement :

```json
{"v": 1, "id": "…", "type": "service.ready", "namespace": "prod", "subject": "lobby-1",
 "task": "lobby", "environment": "server", "data": {"name": "lobby-1", "spec": {…}, "status": {…}},
 "timestamp": 1760000000000}
```

Les champs inconnus sont ignorés : un composant plus ancien lit les messages d'un composant plus récent.

## Événements publiés par le controller

| Type | Quand | `data` |
|---|---|---|
| `service.created` | premier traitement d'un service | instantané du service |
| `service.phase-changed` | à chaque changement de phase | instantané + `from`, `to` |
| `service.ready` | le service atteint `Running` | instantané (adresse comprise) |
| `service.stopped` | `Stopped` ou `Failed` | instantané |
| `service.deleted` | service supprimé par sa task | instantané |
| `service.frozen` | le chien de garde d'un proxy l'a déclaré figé (il va redémarrer) | instantané + `reason` |
| `task.updated` | la configuration effective d'une task change | la task |
| `task.degraded` | la task passe en crash-loop | la task |

Les modules publient sous leur préfixe : `module.<id>.<nom>`.

## Utilisation en Java

```java
MessagingTransport messaging = …; // fourni par Ksmc aux modules (p06) et aux plugins (plugin-api)

// Écouter un channel
messaging.channels().subscribe("party", msg -> {
    Invite invite = msg.data(Invite.class);
    return null;                         // ou une réponse si l'émetteur fait une requête
});

// Envoyer à une task
messaging.channels().send(ChannelMessage.of("party", "invite")
        .target(Target.task("bedwars")).data(new Invite("Steve")));

// Requête avec réponse
messaging.channels().request(ChannelMessage.of("shop", "price?").target(Target.service("shop-1")), 2000)
        .thenAccept(reply -> System.out.println(reply.data));

// Événements, avec reprise après redémarrage
messaging.events().subscribe(EventFilter.type("service.*").environment("server").sinceSequence(lastSeq),
        event -> lastSeq = event.sequence);

// Clé-valeur
KeyValueApi kv = messaging.keyValue("shared");
long rev = kv.update("games-played", "1".getBytes(UTF_8), 0);   // 0 = la clé ne doit pas exister
kv.update("games-played", "2".getBytes(UTF_8), rev);            // MessagingException "Conflict" si modifiée entre-temps
```

## Sécurité

Le chart crée deux comptes NATS (mots de passe aléatoires dans le Secret `ksmc-nats-auth`) :

- `controller` : tous les droits ;
- `service` (Pods Minecraft) : **interdit** de publier des événements (`ksmc.*.ev.>`), des commandes
  console (`ksmc.*.cmd.>`) et de créer/modifier/supprimer des streams JetStream.

Limite connue (v1) : l'isolation entre namespaces repose sur les sujets ; pour des locataires mutuellement
hostiles, utiliser des installations NATS séparées (`nats.enabled=false`, `nats.externalUrl`).

## Envoyer une commande à la main

```bash
kubectl -n ksmc port-forward svc/ksmc-nats 4222 &
PASS=$(kubectl -n ksmc get secret ksmc-nats-auth -o jsonpath='{.data.controller-password}' | base64 -d)
nats --server nats://controller:$PASS@127.0.0.1:4222 req ksmc.ksmc.cmd.lobby-1 "say Bonjour"
```
