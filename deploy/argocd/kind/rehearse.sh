#!/usr/bin/env bash
# GitOps rehearsal on kind (S-15, docs/runbooks/gitops.md § Local rehearsal): Argo CD from deploy/argocd/install, the
# app of apps of envs/local, and a Git repository that stands in for GitHub/GitLab — a bare repository on this machine,
# mounted into the kind node and read by argocd-repo-server as file:///gitops/northline.git.
#
#   deploy/argocd/kind/rehearse.sh up      cluster + Postgres/Valkey stand-ins + Argo CD + bootstrap (≈ 5 min)
#   deploy/argocd/kind/rehearse.sh push    commit the working tree as it is now to the rehearsal repository — the
#                                          equivalent of merging a PR; Argo CD picks it up (or: argocd app refresh)
#   deploy/argocd/kind/rehearse.sh down    delete the cluster, the Postgres container and the rehearsal repository
#
# Needs docker, kind, kubectl, helm, git and the images built locally as northline/<app>:$IMAGE_TAG (deploy.md § kind).
# Variables: KIND_CLUSTER (northline-gitops), IMAGE_TAG (dev), APPS ("api auth bff": envs/local/values.yaml disables
# the others), STATE_DIR (~/.cache/northline-gitops-rehearsal), ARGOCD_IMAGE / ARGOCD_REDIS_IMAGE (mirrors of
# quay.io/argoproj/argocd and the Redis image when those registries are unreachable; the image must provide
# /usr/local/bin/argocd* like upstream's), KIND_NODE_IMAGE (kind's node image).
set -euo pipefail

cmd=${1:-up}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
CLUSTER=${KIND_CLUSTER:-northline-gitops}
TAG=${IMAGE_TAG:-dev}
APPS=${APPS-"api auth bff"}
STATE=${STATE_DIR:-$HOME/.cache/northline-gitops-rehearsal}
BARE="$STATE/northline.git"
WORK="$STATE/work"
REPO_URL=file:///gitops/northline.git
export GIT_AUTHOR_NAME=rehearsal GIT_AUTHOR_EMAIL=rehearsal@northline.invalid
export GIT_COMMITTER_NAME=rehearsal GIT_COMMITTER_EMAIL=rehearsal@northline.invalid

# The working tree (tracked + untracked, not ignored) → one commit on main of the rehearsal repository, with the
# local environment pointed at that repository and at the locally built images.
push() {
  mkdir -p "$STATE"
  [[ -d $BARE ]] || git init -q --bare -b main "$BARE"
  [[ -d $WORK/.git ]] || git clone -q "$BARE" "$WORK" 2>/dev/null || git init -q -b main "$WORK"
  git -C "$WORK" remote get-url origin >/dev/null 2>&1 || git -C "$WORK" remote add origin "$BARE"
  find "$WORK" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  (cd "$ROOT" && git ls-files -z --cached --others --exclude-standard | xargs -0 -I{} cp --parents -- {} "$WORK/" 2>/dev/null) || true
  sed -i "s|^repoURL: .*|repoURL: $REPO_URL|" "$WORK/deploy/argocd/envs/local/env.yaml"
  sed -i "s|^    tag: .*|    tag: $TAG|" "$WORK/deploy/argocd/envs/local/images.yaml"
  git -C "$WORK" add -A
  git -C "$WORK" commit -q -m "rehearsal: $(git -C "$ROOT" rev-parse --short HEAD) + working tree" || echo "nothing changed"
  git -C "$WORK" push -q origin HEAD:main
  echo "pushed $(git -C "$WORK" rev-parse --short HEAD) to $REPO_URL"
}

up() {
  push
  if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    cat >"$STATE/cluster.yaml" <<YAML
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraMounts:
      - hostPath: $STATE
        containerPath: /gitops
        readOnly: true
YAML
    kind create cluster --name "$CLUSTER" --config "$STATE/cluster.yaml" --wait 180s ${KIND_NODE_IMAGE:+--image "$KIND_NODE_IMAGE"}
  fi
  kubectl config use-context "kind-$CLUSTER" >/dev/null

  # Postgres beside the cluster, Valkey and the endpoint-less Kafka/Elasticsearch Services, the images — no Helm.
  SKIP_HELM=1 KIND_CLUSTER=$CLUSTER IMAGE_TAG=$TAG APPS=$APPS "$ROOT/deploy/kind/up.sh"

  # Argo CD, as deploy/argocd/install defines it, plus: the rehearsal repository mounted into the repo server, and no
  # Dex (no SSO in the rehearsal).
  kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl kustomize "$ROOT/deploy/argocd/install" >"$STATE/argocd.yaml"
  if [[ -n ${ARGOCD_IMAGE:-} ]]; then
    # A local copy loaded into the node: don't let upstream's imagePullPolicy: Always look for it in a registry.
    sed -i -E -e "s#quay.io/argoproj/argocd:v[0-9.]+#$ARGOCD_IMAGE#" -e 's#imagePullPolicy: Always#imagePullPolicy: IfNotPresent#' "$STATE/argocd.yaml"
  fi
  if [[ -n ${ARGOCD_REDIS_IMAGE:-} ]]; then
    sed -i -E "s#image: [^ ]*/redis:[^ ]+#image: $ARGOCD_REDIS_IMAGE#" "$STATE/argocd.yaml"
  fi
  for image in ${ARGOCD_IMAGE:-} ${ARGOCD_REDIS_IMAGE:-}; do
    if docker exec "$CLUSTER-control-plane" ctr --namespace=k8s.io images ls -q | grep -q "/${image%%:*}:${image##*:}$"; then continue; fi
    if docker image inspect "$image" >/dev/null 2>&1; then
      docker save "$image" | docker exec -i "$CLUSTER-control-plane" ctr --namespace=k8s.io images import - >/dev/null
      echo "loaded $image"
    fi
  done
  mkdir -p "$STATE/argocd" && mv "$STATE/argocd.yaml" "$STATE/argocd/install.yaml"
  cat >"$STATE/argocd/kustomization.yaml" <<'YAML'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources: [install.yaml]
configMapGenerator:
  # The rehearsal repository belongs to another user than the repo server's; git refuses it without this. (Argo CD
  # runs git with its own environment, so GIT_CONFIG_* variables don't reach it — a system gitconfig does.)
  - name: rehearsal-gitconfig
    options: { disableNameSuffixHash: true }
    literals:
      - |
        gitconfig=[safe]
        	directory = *
patches:
  - target: { kind: Deployment, name: argocd-dex-server }
    patch: |
      - { op: replace, path: /spec/replicas, value: 0 }
  - target: { kind: Deployment, name: argocd-repo-server }
    patch: |
      apiVersion: apps/v1
      kind: Deployment
      metadata: { name: argocd-repo-server }
      spec:
        template:
          spec:
            volumes:
              - { name: gitops, hostPath: { path: /gitops } }
              - { name: rehearsal-gitconfig, configMap: { name: rehearsal-gitconfig } }
            containers:
              - name: argocd-repo-server
                volumeMounts:
                  - { name: gitops, mountPath: /gitops, readOnly: true }
                  - { name: rehearsal-gitconfig, mountPath: /etc/gitconfig, subPath: gitconfig }
YAML
  kubectl apply -n argocd --server-side --force-conflicts -k "$STATE/argocd" >/dev/null
  for d in argocd-server argocd-repo-server argocd-redis; do kubectl -n argocd rollout status deployment "$d" --timeout 5m; done
  kubectl -n argocd rollout status statefulset argocd-application-controller --timeout 5m

  # Bootstrap: the one manual step. From here on the root Application manages itself from Git.
  helm template root "$WORK/deploy/argocd/app-of-apps" -f "$WORK/deploy/argocd/envs/local/env.yaml" \
    | kubectl apply -n argocd -f -
  echo "waiting for northline-local to be Synced and Healthy…"
  for _ in $(seq 1 120); do
    s=$(kubectl -n argocd get application northline-local -o jsonpath='{.status.sync.status}/{.status.health.status}' 2>/dev/null || true)
    [[ $s == Synced/Healthy ]] && break
    sleep 5
  done
  kubectl -n argocd get applications
  kubectl -n northline-local get pods,jobs
}

down() {
  kind delete cluster --name "$CLUSTER"
  docker rm -f "${CLUSTER}-postgres" >/dev/null 2>&1 || true
  rm -rf "$STATE"
}

case $cmd in
  up) up ;;
  push) push ;;
  down) down ;;
  *) sed -n '2,17p' "$0"; exit 2 ;;
esac
