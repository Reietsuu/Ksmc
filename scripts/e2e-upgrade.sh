#!/usr/bin/env bash
# Montée de version du chart sur un cluster kind éphémère : installe la version précédente (dernier tag v*,
# sinon origin/main, avec ses images publiées sur ghcr.io), crée un service statique avec des données, puis met à
# jour vers le code courant et vérifie que le service et ses fichiers ont survécu.
# Usage : scripts/e2e-upgrade.sh [--keep]     Variables : KSMC_UPGRADE_FROM=<ref git> (défaut : auto)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CLUSTER=${KSMC_CLUSTER:-ksmc-upgrade}
NAMESPACE=ksmc
TAG=e2e
KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

scripts/tools.sh >/dev/null
export PATH="$ROOT/.tools/bin:$PATH"
export KUBECONFIG="$ROOT/.tools/kubeconfig-$CLUSTER"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }
k() { kubectl -n "$NAMESPACE" "$@"; }

cleanup() {
  local code=$?
  if [[ $code -ne 0 && -f "$KUBECONFIG" ]]; then
    k get all,mctask,mcsvc,pvc -o wide || true
    k logs -l app.kubernetes.io/component=controller --tail=100 || true
  fi
  [[ "$KEEP" == false ]] && kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  rm -rf "${PREV_DIR:-}"
  if [[ $code -eq 0 ]]; then printf '\n\033[1;32mUPGRADE : SUCCÈS\033[0m\n'; else printf '\n\033[1;31mUPGRADE : ÉCHEC (code %s)\033[0m\n' "$code"; fi
  exit $code
}
trap cleanup EXIT

# Version précédente : ref git du chart + tag des images publiées pour cette ref.
git fetch -q --tags origin || true
FROM=${KSMC_UPGRADE_FROM:-$(git describe --tags --abbrev=0 --match 'v*' origin/main 2>/dev/null || echo origin/main)}
if [[ "$FROM" =~ ^v([0-9].*)$ ]]; then PREV_TAG=${BASH_REMATCH[1]}; else PREV_TAG="sha-$(git rev-parse --short=7 "$FROM")"; fi
PREV_DIR=$(mktemp -d)
git archive "$FROM" deploy/helm/ksmc | tar -x -C "$PREV_DIR"
PREV_CHART="$PREV_DIR/deploy/helm/ksmc"
echo "version précédente : $FROM (images $PREV_TAG)"

step "Cluster kind '$CLUSTER'"
kind get clusters | grep -qx "$CLUSTER" || {
  config=$(mktemp); sed '/extraPortMappings/,$d' scripts/kind-config.yaml > "$config"
  kind create cluster --name "$CLUSTER" --config "$config" --wait 120s; rm -f "$config"; }
kind export kubeconfig --name "$CLUSTER" >/dev/null

step "Installation de la version précédente ($FROM)"
helm dependency update "$PREV_CHART" >/dev/null
kubectl apply --server-side -f "$PREV_CHART/crds/" >/dev/null
helm install ksmc "$PREV_CHART" -n "$NAMESPACE" --create-namespace --skip-crds \
  --set controller.image.tag="$PREV_TAG" --set runtime.image.tag="$PREV_TAG" --set minecraft.acceptEula=true \
  --wait --timeout 8m

step "Service statique avec des données"
k apply -f - <<'YAML'
apiVersion: ksmc.io/v1alpha1
kind: ServiceTask
metadata: {name: keep}
spec:
  environment: proxy
  software: {name: velocity, version: latest}
  memoryMb: 512
  minServiceCount: 1
  static: true
  storage: {size: 1Gi}
YAML
wait_running() {
  for _ in $(seq 1 120); do
    [[ "$(k get mcsvc keep-1 -o jsonpath='{.status.phase}' 2>/dev/null)" == Running ]] && return 0
    sleep 5
  done
  fail "keep-1 n'est pas Running"
}
wait_running
POD=$(k get mcsvc keep-1 -o jsonpath='{.status.podName}')
k exec "$POD" -c server -- sh -c 'echo "données d avant la mise à jour" > /server/marker.txt'
UID_BEFORE=$(k get mcsvc keep-1 -o jsonpath='{.metadata.uid}')
PVC_BEFORE=$(k get pvc -o name | sort)

step "Build des images courantes"
./gradlew -q :ksmc-controller:installDist :ksmc-wrapper:jar
docker build -q -f docker/controller.Dockerfile -t "ghcr.io/reietsuu/ksmc-controller:$TAG" . >/dev/null
docker build -q -f docker/runtime.Dockerfile --build-arg JAVA_VERSION=21 -t "ghcr.io/reietsuu/ksmc-runtime:$TAG-java21" . >/dev/null
kind load docker-image --name "$CLUSTER" "ghcr.io/reietsuu/ksmc-controller:$TAG" "ghcr.io/reietsuu/ksmc-runtime:$TAG-java21"

step "Mise à jour vers la version courante"
helm dependency update deploy/helm/ksmc >/dev/null
kubectl apply --server-side --force-conflicts -f deploy/helm/ksmc/crds/ >/dev/null
helm upgrade ksmc deploy/helm/ksmc -n "$NAMESPACE" --skip-crds \
  --set controller.image.tag="$TAG" --set controller.image.pullPolicy=Never \
  --set runtime.image.tag="$TAG" --set minecraft.acceptEula=true --wait --timeout 8m
k rollout status deployment/ksmc-controller --timeout=180s >/dev/null

step "Vérifications"
[[ "$(k get mcsvc keep-1 -o jsonpath='{.metadata.uid}')" == "$UID_BEFORE" ]] || fail "keep-1 a été recréé"
[[ "$(k get pvc -o name | sort)" == "$PVC_BEFORE" ]] || fail "volumes modifiés"
# Le nouveau controller reprend le service ; si le Pod est recréé (nouvelle image), les données restent.
sleep 20
wait_running
POD=$(k get mcsvc keep-1 -o jsonpath='{.status.podName}')
k exec "$POD" -c server -- cat /server/marker.txt | grep -q "données d avant" || fail "fichier du service statique perdu"
# Fonctionnalité de la version courante : métriques du controller (calculées peu après son démarrage).
metrics_ok() {
  k exec deploy/ksmc-controller -- bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /metrics HTTP/1.0\r\n\r\n" >&3; cat <&3' \
    | grep '^ksmc_services{phase="Running",task="keep"} 1.0' >/dev/null
}
for _ in $(seq 1 12); do metrics_ok && break; sleep 5; done
metrics_ok || fail "métriques absentes après mise à jour"
echo "service statique conservé (uid, volume, fichier), controller à jour"
