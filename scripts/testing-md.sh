#!/usr/bin/env bash
# Exécute les blocs « ```bash test » de docs/TESTING.md, dans l'ordre et dans un même shell, sur le cluster courant
# (KUBECONFIG). Utilisé à la fin de scripts/e2e-kind.sh pour vérifier la procédure de test manuelle.
# Prérequis : Ksmc installé dans le namespace ksmc, CLI construite (./gradlew :ksmc-cli:installDist).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$ROOT/ksmc-cli/build/install/ksmc/bin:$PATH"
export KSMC_CONFIG=${KSMC_CONFIG:-$(mktemp -d)/config.yaml}
script=$(mktemp)
{
  echo 'set -euo pipefail'
  echo 'trap '"'"'echo "ÉCHEC du bloc : ligne $LINENO : $BASH_COMMAND" >&2'"'"' ERR'
  awk '
    /^```bash test$/ { inblock = 1; n++; printf "echo \"--- bloc %d\"\n", n; next }
    /^```$/ && inblock { inblock = 0; next }
    inblock { print }
  ' "$ROOT/docs/TESTING.md"
  echo 'echo "TESTING.md : tous les blocs vérifiés"'
} > "$script"
cd "$ROOT"
code=0
bash "$script" || code=$?
pkill -f "kubectl -n ksmc port-forward" || true
rm -f "$script"
exit $code
