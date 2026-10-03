#!/usr/bin/env bash
# S-119: prints the path of the k6 binary the load tests use, building it once into loadtest/.bin.
#
# Stock k6 has no server-sent events client, and the kitchen display (KDS) scenario holds hundreds of SSE streams, so
# the binary is k6 with the xk6-sse extension (github.com/phymbert/xk6-sse), both pinned. Built with:
#   1. K6=/path/to/k6       an existing build with k6/x/sse (nothing is built)
#   2. Go (any 1.24+)       go install of xk6, then xk6 build — about a minute, ~300 MB of module cache
#   3. Docker               the grafana/xk6 image does the same build in a container
# docs/runbooks/load-testing.md § Tooling.
set -euo pipefail

K6_VERSION=v1.3.0
XK6_VERSION=v1.4.14
SSE_VERSION=v0.1.12 # the last xk6-sse release for k6 v1 (v0.2 needs k6 v2)

if [ -n "${K6:-}" ]; then
  echo "$K6"
  exit 0
fi

bin="$(cd "$(dirname "$0")" && pwd)/.bin"
k6="$bin/k6-$K6_VERSION-sse-$SSE_VERSION"
if [ -x "$k6" ]; then
  echo "$k6"
  exit 0
fi
mkdir -p "$bin"

if command -v go >/dev/null 2>&1; then
  echo "Building k6 $K6_VERSION with xk6-sse $SSE_VERSION (Go, once) …" >&2
  GOBIN="$bin" go install "go.k6.io/xk6/cmd/xk6@$XK6_VERSION" >&2
  "$bin/xk6" build "$K6_VERSION" --with "github.com/phymbert/xk6-sse@$SSE_VERSION" --output "$k6" >&2
elif command -v docker >/dev/null 2>&1; then
  echo "Building k6 $K6_VERSION with xk6-sse $SSE_VERSION (grafana/xk6 in Docker, once) …" >&2
  docker run --rm -u "$(id -u):$(id -g)" -e GOOS="$(uname -s | tr '[:upper:]' '[:lower:]')" -v "$bin:/xk6" \
    "grafana/xk6:${XK6_VERSION#v}" build "$K6_VERSION" --with "github.com/phymbert/xk6-sse@$SSE_VERSION" \
    --output "/xk6/$(basename "$k6")" >&2
else
  echo "k6 with xk6-sse is needed: install Go 1.24+ or Docker, or point K6= at such a build (loadtest/k6.sh)." >&2
  exit 1
fi
"$k6" version >&2
echo "$k6"
