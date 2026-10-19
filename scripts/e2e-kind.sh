#!/usr/bin/env bash
# Tests de bout en bout sur un cluster kind éphémère.
# Usage : scripts/e2e-kind.sh [--keep] [--skip-build]
#   --keep        conserve le cluster après les tests (pour tester à la main)
#   --skip-build  réutilise l'image déjà chargée
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CLUSTER=${KSMC_CLUSTER:-ksmc}
NAMESPACE=${KSMC_NAMESPACE:-ksmc}
TAG=e2e
KEEP=false; SKIP_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=true ;;
    --skip-build) SKIP_BUILD=true ;;
    *) echo "argument inconnu : $arg" >&2; exit 1 ;;
  esac
done

scripts/tools.sh >/dev/null
export PATH="$ROOT/.tools/bin:$PATH"
# Kubeconfig dédié : ne jamais toucher à la configuration kubectl de la machine.
export KUBECONFIG="$ROOT/.tools/kubeconfig-$CLUSTER"
# Port hôte optionnel vers le NodePort 30565 du proxy (ex. KSMC_HOST_PORT=25566). Aucun par défaut.
HOST_PORT=${KSMC_HOST_PORT:-}

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

diagnostics() {
  [[ -f "$KUBECONFIG" ]] || return 0
  step "Diagnostic"
  kubectl get all,mctask,mcsvc -n "$NAMESPACE" -o wide || true
  kubectl get events -n "$NAMESPACE" --sort-by=.lastTimestamp | tail -30 || true
  kubectl logs -n "$NAMESPACE" -l app.kubernetes.io/component=controller --tail=200 || true
}

cleanup() {
  local code=$?
  [[ $code -ne 0 ]] && diagnostics
  if [[ "$KEEP" == false ]]; then kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true; fi
  if [[ $code -eq 0 ]]; then printf '\n\033[1;32mE2E : SUCCÈS\033[0m\n'; else printf '\n\033[1;31mE2E : ÉCHEC (code %s)\033[0m\n' "$code"; fi
  exit $code
}
trap cleanup EXIT

step "Cluster kind '$CLUSTER'"
if ! kind get clusters | grep -qx "$CLUSTER"; then
  config=$(mktemp)
  if [[ -n "$HOST_PORT" ]]; then
    sed "s/hostPort: 25565/hostPort: $HOST_PORT/" scripts/kind-config.yaml > "$config"
  else
    sed '/extraPortMappings/,$d' scripts/kind-config.yaml > "$config"
  fi
  kind create cluster --name "$CLUSTER" --config "$config" --wait 120s
  rm -f "$config"
fi
kind export kubeconfig --name "$CLUSTER" >/dev/null

if [[ "$SKIP_BUILD" == false ]]; then
  step "Build et chargement des images"
  ./gradlew -q :ksmc-controller:installDist :ksmc-wrapper:jar
  docker build -q -f docker/controller.Dockerfile -t "ghcr.io/reietsuu/ksmc-controller:$TAG" . >/dev/null
  kind load docker-image --name "$CLUSTER" "ghcr.io/reietsuu/ksmc-controller:$TAG"
  # Variantes Java des images runtime utilisées par les scénarios (KSMC_E2E_JAVA, défaut : "21 25").
  for java in ${KSMC_E2E_JAVA:-21 25}; do
    docker build -q -f docker/runtime.Dockerfile --build-arg JAVA_VERSION="$java" \
      -t "ghcr.io/reietsuu/ksmc-runtime:$TAG-java$java" . >/dev/null
    kind load docker-image --name "$CLUSTER" "ghcr.io/reietsuu/ksmc-runtime:$TAG-java$java"
  done
fi

step "Installation du chart"
helm dependency update deploy/helm/ksmc >/dev/null
kubectl apply --server-side -f deploy/helm/ksmc/crds/ >/dev/null
helm upgrade --install ksmc deploy/helm/ksmc -n "$NAMESPACE" --create-namespace --skip-crds \
  --set controller.image.tag="$TAG" --set controller.image.pullPolicy=Never \
  --set runtime.image.tag="$TAG" --set minecraft.acceptEula=true \
  --wait --timeout 5m
# Même tag d'image entre deux exécutions : forcer le redémarrage pour prendre la nouvelle image.
kubectl -n "$NAMESPACE" rollout restart deployment/ksmc-controller >/dev/null
kubectl -n "$NAMESPACE" rollout status deployment/ksmc-controller --timeout=180s >/dev/null

step "Exemples (module et plugin)"
./gradlew -q -p examples/module-hello build
./gradlew -q -p examples/plugin-hello build

step "Scénarios e2e"
KSMC_NAMESPACE="$NAMESPACE" ./gradlew -q :ksmc-e2e:test -Pe2e --rerun-tasks
