#!/usr/bin/env bash
# Installe kind, kubectl et helm (versions épinglées) dans ./.tools/bin.
# Usage : source <(scripts/tools.sh env)   ou   scripts/tools.sh
set -euo pipefail

KIND_VERSION=v0.33.0
KUBECTL_VERSION=v1.37.1
HELM_VERSION=v4.3.1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/.tools/bin"
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"; case "$ARCH" in x86_64) ARCH=amd64;; aarch64|arm64) ARCH=arm64;; esac

if [[ "${1:-}" == "env" ]]; then echo "export PATH=\"$BIN:\$PATH\""; exit 0; fi
mkdir -p "$BIN"

if [[ ! -x "$BIN/kind" ]]; then
  curl -fsSLo "$BIN/kind" "https://kind.sigs.k8s.io/dl/${KIND_VERSION}/kind-${OS}-${ARCH}" && chmod +x "$BIN/kind"
fi
if [[ ! -x "$BIN/kubectl" ]]; then
  curl -fsSLo "$BIN/kubectl" "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/${OS}/${ARCH}/kubectl" && chmod +x "$BIN/kubectl"
fi
if [[ ! -x "$BIN/helm" ]]; then
  curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-${OS}-${ARCH}.tar.gz" | tar -xz -C "$BIN" --strip-components=1 "${OS}-${ARCH}/helm"
fi
"$BIN/kind" version; "$BIN/kubectl" version --client | head -1; "$BIN/helm" version --short
