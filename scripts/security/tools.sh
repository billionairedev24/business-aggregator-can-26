#!/usr/bin/env bash
# S-104 — pinned copies of the security scanners in .cache/security-tools (make security-tools). Binaries come from the
# projects' GitHub releases; semgrep and checkov from PyPI into a virtualenv. Re-running skips what is there.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TOOLS=${SECURITY_TOOLS_DIR:-$ROOT/.cache/security-tools}
GITLEAKS_VERSION=${GITLEAKS_VERSION:-8.21.2}
OSV_SCANNER_VERSION=${OSV_SCANNER_VERSION:-1.9.2}
KUBE_SCORE_VERSION=${KUBE_SCORE_VERSION:-1.19.0}
SEMGREP_VERSION=${SEMGREP_VERSION:-1.179.0}
CHECKOV_VERSION=${CHECKOV_VERSION:-3.3.20}

mkdir -p "$TOOLS/bin"
os=$(uname -s | tr '[:upper:]' '[:lower:]')
case "$(uname -m)" in x86_64 | amd64) arch=amd64; garch=x64 ;; arm64 | aarch64) arch=arm64; garch=arm64 ;; *) arch=$(uname -m); garch=$arch ;; esac

fetch() { echo "  $1"; curl -fsSL --retry 3 -o "$2" "$1"; }

if [ ! -x "$TOOLS/bin/gitleaks" ]; then
  fetch "https://github.com/gitleaks/gitleaks/releases/download/v$GITLEAKS_VERSION/gitleaks_${GITLEAKS_VERSION}_${os}_${garch}.tar.gz" "$TOOLS/gitleaks.tgz"
  tar -xzf "$TOOLS/gitleaks.tgz" -C "$TOOLS/bin" gitleaks && rm "$TOOLS/gitleaks.tgz"
fi
if [ ! -x "$TOOLS/bin/osv-scanner" ]; then
  fetch "https://github.com/google/osv-scanner/releases/download/v$OSV_SCANNER_VERSION/osv-scanner_${os}_${arch}" "$TOOLS/bin/osv-scanner"
  chmod +x "$TOOLS/bin/osv-scanner"
fi
if [ ! -x "$TOOLS/bin/kube-score" ]; then
  fetch "https://github.com/zegl/kube-score/releases/download/v$KUBE_SCORE_VERSION/kube-score_${KUBE_SCORE_VERSION}_${os}_${arch}.tar.gz" "$TOOLS/kube-score.tgz"
  tar -xzf "$TOOLS/kube-score.tgz" -C "$TOOLS/bin" kube-score && rm "$TOOLS/kube-score.tgz"
fi
if [ ! -x "$TOOLS/venv/bin/semgrep" ] || [ ! -x "$TOOLS/venv/bin/checkov" ]; then
  python3 -m venv "$TOOLS/venv"
  "$TOOLS/venv/bin/pip" install -q "semgrep==$SEMGREP_VERSION" "checkov==$CHECKOV_VERSION"
fi
echo "Security tools in $TOOLS (bin/, venv/bin/)"
