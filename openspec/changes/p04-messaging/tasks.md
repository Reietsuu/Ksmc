# Tasks

## 1. Protocole

- [ ] 1.1 `ksmc-wire` : `ChannelMessage`, `Target`, `Sender`, `CloudEvent`, `RpcError`, codec JSON versionné ; tests de compatibilité (champ inconnu ignoré)
- [ ] 1.2 Interfaces `MessagingTransport`, `ChannelApi`, `EventApi`, `KeyValueApi` dans `ksmc-wire` (Java 8) ; vérifier compilation `--release 8`

## 2. Transport NATS

- [ ] 2.1 `ksmc-messaging-nats` : publication/abonnement ciblés, request/reply avec timeout ; tests avec conteneur NATS (Testcontainers) 
- [ ] 2.2 Événements JetStream (stream, rejouer depuis séquence/heure) et KV (CAS, watch, TTL) ; tests Testcontainers
- [ ] 2.3 Comptes NATS `controller`/`service` dans le chart + Secret ; test e2e de refus de publication d'événement par un service

## 3. Intégration

- [ ] 3.1 Controller : connexion, readiness NATS, publication des événements service/task depuis les reconcilers ; tests unitaires avec transport en mémoire
- [ ] 3.2 Wrapper : connexion NATS, réception des commandes console → stdin + accusé ; test d'intégration
- [ ] 3.3 Documenter le protocole (sujets, format, exemples) dans `docs/MESSAGING.md` ; vérifier que les exemples du doc sont couverts par les tests
