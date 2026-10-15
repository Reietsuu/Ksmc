# Installation de Ksmc

## Prérequis

- Un cluster Kubernetes ≥ 1.30 (kind, k3s, GKE, EKS, AKS…) et `kubectl` configuré.
- Helm ≥ 3.14 (Helm 4 supporté).
- Une StorageClass par défaut (pour NATS JetStream, le Template Store et les services statiques).

Pour un cluster de test local : `scripts/tools.sh` installe kind, kubectl et helm dans `.tools/bin`,
puis `scripts/e2e-kind.sh --keep` crée un cluster `ksmc` avec Ksmc installé.

## Installer depuis le dépôt

```bash
git clone https://github.com/Reietsuu/Ksmc.git && cd Ksmc
helm dependency update deploy/helm/ksmc
kubectl create namespace ksmc
helm install ksmc deploy/helm/ksmc -n ksmc
```

Vérifier :

```bash
kubectl -n ksmc get pods                      # ksmc-controller-… et ksmc-nats-0 en Running/Ready
kubectl api-resources --api-group=ksmc.io     # 6 types : mctask, mcgroup, mctpl, mcsvc, mcsw, mcmod
```

## Valeurs principales

| Valeur | Défaut | Rôle |
|---|---|---|
| `controller.image.repository` / `tag` | `ghcr.io/reietsuu/ksmc-controller` / appVersion | Image du controller |
| `controller.resources` | 384Mi → 768Mi | Ressources du controller |
| `controller.logLevel` | `INFO` | Niveau de log |
| `nats.enabled` | `true` | Déployer NATS (JetStream) avec Ksmc |
| `nats.externalUrl` | — | URL d'un NATS existant si `nats.enabled=false` |
| `nats.config.jetstream.fileStore.pvc.size` | `2Gi` | Stockage JetStream |

Exemple avec un NATS existant :

```bash
helm install ksmc deploy/helm/ksmc -n ksmc --set nats.enabled=false --set nats.externalUrl=nats://nats.infra:4222
```

## Mettre à jour

```bash
git pull && helm dependency update deploy/helm/ksmc
kubectl apply --server-side -f deploy/helm/ksmc/crds/   # Helm n'actualise pas les CRDs
helm upgrade ksmc deploy/helm/ksmc -n ksmc
```

## Désinstaller

```bash
helm uninstall ksmc -n ksmc
```

Les CRDs et les volumes (PVC) des services statiques sont **conservés**. Pour tout supprimer :

```bash
kubectl delete namespace ksmc
kubectl get crd -o name | grep ksmc.io | xargs kubectl delete
```
