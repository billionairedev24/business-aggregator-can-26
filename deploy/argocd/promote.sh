#!/usr/bin/env bash
# Promotion (S-15): writes deploy/argocd/envs/<env>/images.yaml — the registry, the commit and one digest per image —
# for a PR. Argo CD deploys what that file names once the PR is merged (dev: by itself; staging/prod: when a deployer
# syncs). docs/runbooks/gitops.md § Promotion
#
#   promote.sh <env> --registry <registry/path> --tag <git sha>   resolve each image's digest in the registry
#   promote.sh <env> --digests <file>  [--registry …] [--tag …]    digests from the build ("<app> sha256:…" per line,
#                                                                  the GitLab image-digests.txt / GitHub run summary)
#   promote.sh <env> --from <env>      [--registry …]              the exact digests another environment runs
#                                                                  (staging → prod); --registry when the target
#                                                                  environment pulls from another registry the images
#                                                                  were copied to (crane copy keeps the digest)
# Options: --apps "api auth …" (default: every image). Digest lookup needs crane, skopeo or docker buildx, and
# registry credentials (docker login).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ALL_APPS="api auth bff worker studio consumer"
usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

[[ $# -ge 1 ]] || usage
env=$1; shift
case $env in dev | staging | prod) ;; *) echo "environment must be dev, staging or prod" >&2; exit 2 ;; esac
registry="" tag="" from="" digests_file="" apps=$ALL_APPS
while [[ $# -gt 0 ]]; do
  case $1 in
    --registry) registry=${2%/}; shift 2 ;;
    --tag) tag=$2; shift 2 ;;
    --from) from=$2; shift 2 ;;
    --digests) digests_file=$2; shift 2 ;;
    --apps) apps=$2; shift 2 ;;
    *) usage ;;
  esac
done

target="$ROOT/deploy/argocd/envs/$env/images.yaml"
declare -A digest

# Reads registry, tag and digests from an images.yaml written by this script.
read_images_file() {
  local file=$1 line app d
  while IFS= read -r line; do
    if [[ $line =~ ^[[:space:]]+registry:[[:space:]]*\"?([^\"[:space:]#]*) ]]; then [[ -n $registry ]] || registry=${BASH_REMATCH[1]}
    elif [[ $line =~ ^[[:space:]]+tag:[[:space:]]*\"?([^\"[:space:]#]*) ]]; then [[ -n $tag ]] || tag=${BASH_REMATCH[1]}
    elif [[ $line =~ ^[[:space:]]+([a-z-]+):[[:space:]]*\{[[:space:]]*image:[[:space:]]*\{[[:space:]]*digest:[[:space:]]*\"?([^\"[:space:]]*)\"? ]]; then
      app=${BASH_REMATCH[1]} d=${BASH_REMATCH[2]}
      digest[$app]=$d
    fi
  done <"$file"
}

resolve_digest() {
  local ref=$1
  if command -v crane >/dev/null; then crane digest "$ref"
  elif command -v skopeo >/dev/null; then skopeo inspect --format '{{.Digest}}' "docker://$ref"
  elif docker buildx version >/dev/null 2>&1; then
    # The index (or manifest) digest is the first "digest" of the pretty-printed JSON.
    docker buildx imagetools inspect "$ref" --format '{{json .Manifest}}' | grep -m1 '"digest"' |
      sed -E 's/.*"(sha256:[0-9a-f]{64})".*/\1/'
  else echo "need crane, skopeo or docker buildx to look up digests" >&2; return 1
  fi
}

if [[ -n $from ]]; then
  src="$ROOT/deploy/argocd/envs/$from/images.yaml"
  [[ -f $src ]] || { echo "no $src" >&2; exit 1; }
  read_images_file "$src"
else
  # Keep what the target runs for the apps not being promoted.
  read_images_file "$target"
  [[ -n $registry && -n $tag ]] || { echo "--registry and --tag are required (or --from <env>)" >&2; exit 2; }
  if [[ -n $digests_file ]]; then
    while read -r app d _; do
      [[ -n ${app:-} && $d == sha256:* ]] && [[ " $apps " == *" $app "* ]] && digest[$app]=$d
    done <"$digests_file"
  else
    for app in $apps; do
      digest[$app]=$(resolve_digest "$registry/$app:$tag")
      echo "$app $registry/$app:$tag → ${digest[$app]}"
    done
  fi
fi

for app in $ALL_APPS; do
  d=${digest[$app]:-}
  if [[ -n $d && ! $d =~ ^sha256:[0-9a-f]{64}$ ]]; then echo "bad digest for $app: $d" >&2; exit 1; fi
done

{
  echo "# Images Northline $env runs, pinned by digest — the promotion file (docs/runbooks/gitops.md § Promotion)."
  echo "# Written by deploy/argocd/promote.sh${from:+ (--from $from)} and changed only through a reviewed PR."
  echo "# The digest is what runs; the tag only says which commit it was built from."
  echo "global:"
  echo "  image:"
  echo "    registry: \"$registry\""
  echo "    tag: \"$tag\""
  echo "    requireDigest: true"
  echo "apps:"
  for app in $ALL_APPS; do echo "  $app: { image: { digest: \"${digest[$app]:-}\" } }"; done
} >"$target.tmp"
mv "$target.tmp" "$target"
echo "wrote ${target#"$ROOT"/}"
git -C "$ROOT" --no-pager diff --stat -- "${target#"$ROOT"/}" 2>/dev/null || true
