# network-exposure Specification

## Purpose
Rendre le réseau joignable depuis l'extérieur du cluster de façon configurable, en s'appuyant sur les Services Kubernetes.

## Requirements

### Requirement: Service Kubernetes par task proxy
Pour chaque task proxy, le controller SHALL créer un Service Kubernetes de type configurable (`LoadBalancer` par défaut,
`NodePort`, `ClusterIP`) qui répartit les connexions sur les proxies `Running` uniquement.

#### Scenario: NodePort
- **WHEN** la task `proxy` déclare `expose.type: NodePort` et `expose.nodePort: 30565`
- **THEN** un client Minecraft peut pinguer le réseau sur `<ip-nœud>:30565`

### Requirement: Annotations et IP source
L'exposition SHALL accepter annotations, labels, `externalTrafficPolicy` et `loadBalancerSourceRanges`, et la
task proxy SHALL pouvoir activer le PROXY protocol pour conserver l'IP réelle des joueurs.

#### Scenario: Annotations de cloud provider
- **WHEN** la task définit une annotation de load balancer
- **THEN** le Service créé porte cette annotation

### Requirement: Serveurs non exposés
Les services `server` SHALL n'être joignables que depuis l'intérieur du cluster ; le chart SHALL fournir une
NetworkPolicy optionnelle limitant l'accès au port Minecraft des serveurs aux proxies.

#### Scenario: NetworkPolicy activée
- **WHEN** `networkPolicy.enabled=true` et un Pod non-proxy tente de joindre le port 25565 d'un serveur
- **THEN** la connexion est refusée
