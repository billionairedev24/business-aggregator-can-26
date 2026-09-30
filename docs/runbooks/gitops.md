# GitOps runbook — Argo CD delivery to dev, staging and prod (S-15)

How Northline gets from a pushed image to pods: Git says what each environment runs, Argo CD in each environment's
cluster makes the cluster match. **dev** follows `main` by itself; **staging** and **prod** change only when a reviewed
PR has been merged *and* a deployer presses Sync. Nothing here depends on the cloud or on the Git host: the same
definitions run on EKS, GKE, AKS or kind, from GitHub or GitLab.

> **Status (2026-09-30):** the definitions exist and are checked offline (`deploy/argocd/validate.sh`); the whole
> flow was rehearsed on kind (below). No cloud cluster exists yet, so Argo CD has not been installed in dev, staging
> or prod, no image has been promoted, and `images.yaml` of every environment is still empty (its Application reports
> a ComparisonError until the first promotion — on purpose).

Other runbooks: [deploy](deploy.md) (images and the chart) · [secrets](secrets.md) · [infrastructure](infrastructure.md) ·
[CI](ci.md) · [dev](dev.md) · [staging](staging.md) · [prod](prod.md)

## The shape

```
deploy/argocd/
├── install/                       Argo CD itself: upstream v3.1.1 manifests + Northline settings (kustomize)
├── app-of-apps/                   Helm chart → the AppProjects and Applications of ONE environment
├── addons/<name>/values[-<cloud>].yaml   platform add-on values (External Secrets Operator; S-17 adds the edge)
├── envs/<env>/                    one directory per environment: dev, staging, prod (+ local = kind rehearsal)
│   ├── env.yaml                   cloud, repository URL, branch, sync policy, deployer groups, add-ons
│   ├── images.yaml                registry + tag + one digest per image — THE promotion file
│   ├── infra.yaml                 terraform output -json helm_values (configEnv, identities, secret names)
│   ├── values.yaml                hand-written chart overrides for this environment
│   └── addons/<name>.yaml         per-environment add-on values (the add-on's cloud identity)
├── promote.sh                     writes images.yaml (digest lookup, or copy from another environment)
├── validate.sh                    offline checks (CI, manual)
└── kind/rehearse.sh               the whole flow on kind
```

- **One Argo CD per environment cluster** (dev, staging and prod are separate clusters, S-6/S-2). Each Argo CD manages
  only the cluster it runs in (`https://kubernetes.default.svc`), so prod credentials never leave prod and a broken
  dev Argo CD can't touch prod. A central Argo CD managing all three would work with the same chart
  (`destination.server` per environment) but was not chosen.
- **App of apps, one per environment.** The root Application `northline-<env>-root` renders `app-of-apps/` with
  `envs/<env>/env.yaml` and manages itself, the three AppProjects and the child Applications. The cloud is a value in
  `env.yaml` (`aws | gcp | azure`), so "environment × cloud" is one chart and nine possible renders (all checked).
  Plain Helm-templated Applications instead of an ApplicationSet: with one Argo CD per cluster there is only one
  environment to generate, and explicit Applications are easier to read and diff.

| Application | project | source | sync |
|---|---|---|---|
| `northline-<env>-root` | `northline-<env>-gitops` | `deploy/argocd/app-of-apps` + `envs/<env>/env.yaml` | automated always (it only writes Application/AppProject objects); prunes only in dev |
| `external-secrets-<env>` | `northline-<env>-platform` | chart `external-secrets` 0.20.4 + `addons/external-secrets/…` + `envs/<env>/addons/external-secrets.yaml` | dev: automated; staging/prod: manual |
| `northline-<env>` | `northline-<env>` | `deploy/helm/northline` with `values-<env>.yaml`, `values-<cloud>.yaml`, then `envs/<env>/infra.yaml`, `values.yaml`, `images.yaml` (later wins) | dev: automated (prune + self-heal); staging/prod: manual |

The northline Application has one source: the chart directory, with the environment's files as value files relative
to it (`../../argocd/envs/<env>/…`), all at `targetRevision`. Sync options: `CreateNamespace`, `ServerSideApply`,
`PruneLast`; server-side diff so fields defaulted by the API server or the External Secrets webhook don't show as
drift.

### Order of a sync

Across Applications (sync waves on the child Applications; the root waits for each wave to be Healthy — the
`argoproj.io/Application` health check in `install/argocd-cm.yaml`): add-ons (wave −10) → northline (wave 0). In
staging/prod the root doesn't wait for children that sync by hand (they count as healthy for the root), so it never
blocks on a human; a deployer syncs `external-secrets-<env>` before `northline-<env>` when both changed.

Inside `northline-<env>` (one sync):

| phase / wave | what | why |
|---|---|---|
| PreSync −20 | ConfigMap, ExternalSecret (or Secret) `northline-migrate-<hash>` with `DB_PASSWORD`; ConfigMap (`KAFKA_*`) and ExternalSecret/Secret (`KAFKA_SASL_JAAS_CONFIG`) `northline-kafka-topics-<hash>` | the migration's inputs exist before the chart's own ConfigMaps/Secrets (first install) |
| PreSync −10 | Jobs `northline-kafka-topics-<hash>` (S-25: creates missing topics from `deploy/kafka/topics.yaml`) and `northline-migrate-<hash>` (Flyway `db/migration`, then the category seed) | S-16: **a failed migration (or topic provisioning) fails the sync before anything else changes**; the running pods keep serving |
| Sync −6 | SecretStore `northline` | |
| Sync −5 | ExternalSecret per app | Argo CD waits until each is Healthy (`SecretSynced`), so the Secrets exist before the Deployments |
| Sync 0 | ConfigMaps, ServiceAccounts, Services, Deployments, HPAs, PDBs, NetworkPolicies, routes | Deployments roll one pod at a time behind readiness |
| PostSync | Job `northline-oauth-clients-<hash>` (Helm `post-install/post-upgrade` hook, mapped by Argo CD) | registers the OAuth clients once everything is Healthy |

Helm hooks become Argo CD hooks (`pre-install/pre-upgrade` → PreSync, `post-*` → PostSync, `before-hook-creation` →
`BeforeHookCreation`); the chart also carries the explicit `argocd.argoproj.io/*` annotations. Argo CD renders with
`helm template`, so there is no Helm release history in the cluster — history and rollback are Argo CD's.

### What each project may do

| project | repositories | destinations | cluster-scoped kinds | namespaced kinds |
|---|---|---|---|---|
| `northline-<env>-gitops` | this repository | namespace `argocd` | none | `Application`, `AppProject` |
| `northline-<env>-platform` | this repository + each enabled add-on's chart repository | each add-on's namespace | `Namespace` + what each add-on declares (`addons.<name>.clusterResources`: CRDs, ClusterRoles, webhooks…) | any |
| `northline-<env>` | this repository | namespace `northline-<env>` only | `Namespace` | the kinds the chart renders (`northline.namespaceResources`): ConfigMap, Secret, Service, ServiceAccount, Deployment, Job, HPA, PDB, NetworkPolicy, Ingress, HTTPRoute, ExternalSecret, SecretStore |

Anything else — another namespace, a ClusterRole from the app chart, a repository nobody listed — is refused by Argo
CD at sync time. `orphanedResources.warn` flags objects in `northline-<env>` that Git doesn't know (hand-made fixes).

## Install Argo CD (once per environment cluster)

Prerequisites: the cluster from Terraform ([infrastructure.md](infrastructure.md)), `kubectl` access
(`terraform output -json kubernetes | jq -r .kubeconfig_command | sh`), and the secrets set in the secrets manager
([secrets.md § Secret inventory](secrets.md#secret-inventory)).

```sh
kubectl create namespace argocd
kubectl apply -n argocd --server-side --force-conflicts -k deploy/argocd/install     # Argo CD v3.1.1 + Northline settings
kubectl -n argocd rollout status deploy/argocd-server
# Terraform values and identities for this environment → Git (PR):
terraform -chdir=infra/terraform/envs/<cloud>/<env> output -json helm_values | yq -P > deploy/argocd/envs/<env>/infra.yaml
#   + the External Secrets identity → deploy/argocd/envs/<env>/addons/external-secrets.yaml (comment in the file)
#   + cloud: <cloud> in deploy/argocd/envs/<env>/env.yaml
# Bootstrap — the only manual apply; from now on the root Application manages itself from Git:
helm template root deploy/argocd/app-of-apps -f deploy/argocd/envs/<env>/env.yaml | kubectl apply -n argocd -f -
```

- `install/` pins the upstream manifest by version (`kustomization.yaml`); upgrading Argo CD is a PR changing that
  version (read the upstream upgrade notes). It adds: the health check that makes the app of apps wait for its
  children (`argocd-cm`), `policy.default: role:readonly` (`argocd-rbac-cm`).
- Google Cloud only: the Memorystore CA ConfigMap `northline-redis-ca` is still created by hand
  ([deploy.md § Install](deploy.md#install-upgrade-roll-back)); a Postgres role and the Kafka topics too
  ([infrastructure.md § 5](infrastructure.md)).
- UI: `kubectl -n argocd port-forward svc/argocd-server 8080:443`, then `https://localhost:8080` (user `admin`,
  password `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d`)
  until SSO is set up; exposing it through the edge is S-17.

### Repository access (GitHub or GitLab)

`repoURL` in `envs/<env>/env.yaml` is the only place the Git host appears. A public repository needs nothing; a private
one needs a repository Secret in `argocd` (never committed):

```sh
# GitHub: a fine-grained token with Contents: read on this repository (or a GitHub App: githubAppID/InstallationID/PrivateKey)
# GitLab: a project or group deploy token with read_repository
kubectl -n argocd create secret generic northline-repo \
  --from-literal=type=git --from-literal=url=https://github.com/<org>/<repo>.git \
  --from-literal=username=<user or deploy-token name> --from-literal=password=<token>
kubectl -n argocd label secret northline-repo argocd.argoproj.io/secret-type=repository
```

SSH (`git@github.com:…`, `git@gitlab.com:…`) works the same with `sshPrivateKey` (a read-only deploy key). Moving the
repository from GitHub to GitLab = change `repoURL` in the four `env.yaml` files and the repository Secret; nothing
else refers to the host. Argo CD polls every 3 minutes; a webhook from GitHub/GitLab to
`https://<argocd>/api/webhook` makes it immediate (optional, needs Argo CD reachable from the Git host).

### Access (who may sync)

- Everyone who can log in to Argo CD is **read-only** (`policy.default: role:readonly`).
- The AppProjects give the groups in `project.deployerGroups` (per environment in `env.yaml`) the `deployer` role:
  get, **sync** (incl. rollback), resource actions and logs — in their environment only. Defaults:
  dev `northline:platform`, `northline:developers`; staging and prod `northline:platform`.
- `northline:platform-admins` administers Argo CD itself (`argocd-rbac-cm`).
- Group names come from SSO: set `dex.config` (GitHub org teams `org:team`, GitLab groups) or `oidc.config` (Entra ID,
  Google Workspace) in `install/argocd-cm.yaml` with the `groups` claim, then `admin.enabled: "false"`. Until then only
  the admin account exists.

## Promotion (build → digest → PR → sync)

```
build (manual CI: deploy workflow / PIPELINE_PART=images)  →  <registry>/<app>:<sha>, digests in the run summary
        ↓ promote.sh dev --registry … --tag <sha>            (CI: gitops workflow action=promote / PIPELINE_PART=promote)
PR "promote dev: <sha>"  → review → merge                   →  Argo CD syncs dev by itself
        ↓ promote.sh staging --from dev
PR "promote staging: <sha>" → CODEOWNERS review → merge     →  a deployer syncs northline-staging (Argo CD UI/CLI)
        ↓ promote.sh prod --from staging
PR "promote prod: <sha>"    → CODEOWNERS review → merge     →  a deployer syncs northline-prod inside the sync window
```

1. **Build** the images (manual CI, [deploy.md § Build and push](deploy.md#build-and-push)). Tags are the commit;
   ECR tags are immutable.
2. **Write the digests.** `deploy/argocd/promote.sh <env> --registry <registry/path> --tag <sha>` looks up every
   image's digest (crane, skopeo or `docker buildx imagetools`; logged in to the registry) and rewrites
   `envs/<env>/images.yaml`; `--digests image-digests.txt` takes them from the GitLab build artifact instead;
   `--from <env>` copies exactly what another environment runs (staging → prod: the bytes that were tested).
   `--apps "api auth"` promotes some images and keeps the others. From CI (manual):
   - GitHub: **Actions › gitops › Run workflow**, `action=promote`, `environment`, `from` or `registry` + `image-tag`
     (`gh workflow run gitops.yml -f action=promote -f environment=staging -f from=dev`). Needs *Settings › Actions ›
     General › Allow GitHub Actions to create and approve pull requests*. The PR is opened by the workflow.
   - GitLab: **Run pipeline** with `PIPELINE_PART=promote`, `PROMOTE_ENV`, `PROMOTE_FROM` or `IMAGE_REGISTRY` +
     `IMAGE_TAG`; needs the masked variable `GITOPS_PUSH_TOKEN` (project access token, Developer, `write_repository`).
     The merge request is opened with push options.
3. **Review and merge** the PR. It shows exactly which digests change. `CODEOWNERS` covers `envs/staging/`,
   `envs/prod/`, `app-of-apps/` and `install/`: with branch protection "Require review from Code Owners" (GitHub) or
   "Code owner approval" on the protected branch (GitLab) nobody promotes staging or prod alone.
4. **Sync.**
   - dev: nothing to do — Argo CD applies the merge within ~3 minutes (prune + self-heal).
   - staging, prod: `northline-<env>` turns **OutOfSync**; a member of the deployer group checks the diff and syncs:
     `argocd app diff northline-<env>` then `argocd app sync northline-<env>` (or the UI's *Sync*). Prod syncs only
     inside its sync window (Mon–Thu from 08:00 America/Edmonton, 9 h; `envs/prod/env.yaml` › `project.syncWindows`).
   - Watch: `argocd app wait northline-<env> --health --timeout 900`; migrations first
     (`kubectl -n northline-<env> logs $(kubectl -n northline-<env> get jobs -l app.kubernetes.io/component=migrate -o name --sort-by=.metadata.creationTimestamp | tail -1) -c migrate`), then the rollout, then the OAuth client Job.

`images.yaml` refuses to render without a digest for every enabled app (`global.image.requireDigest`), so a tag
alone can never reach staging or prod. The chart is read from the same branch, so a chart change (templates, `values-<env>.yaml`) also shows as
OutOfSync in staging/prod and waits for a sync like an image change; the diff shows both. (Pinning the chart to an
older revision than the environment files is not possible with Argo CD: one Application may not read two revisions of
the same repository — see DECISIONS § S-15.)

### Roll back

- **Preferred: revert the promotion PR** (or promote the previous digests with `promote.sh <env> --from …`), merge,
  sync. Git stays the truth.
- **Emergency (staging/prod):** `argocd app history northline-<env>` → `argocd app rollback northline-<env> <id>`
  (deployer role). Argo CD re-applies that revision's manifests; the Application then shows OutOfSync against Git
  until the revert PR lands. dev syncs by itself, so there roll back by revert only.
- Rolling back never undoes migrations (forward-only, [deploy.md § Migrations](deploy.md#migrations-s-16)) nor
  secret values (restore the previous version in the secrets manager).
- A failed migration leaves the sync *Failed* at PreSync with nothing else changed; fix forward (new image, new
  promotion). `argocd app terminate-op northline-<env>` stops a sync that hangs.

### Drift

dev corrects drift by itself (self-heal). In staging/prod a `kubectl edit` shows as OutOfSync and stays until the next
sync overwrites it; `argocd app diff` shows it. Hot fixes go through Git.

## Notifications (optional)

Argo CD notifications ship with the install (`argocd-notifications-controller`). To use them: add the upstream trigger
and template catalog (`kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v3.1.1/notifications_catalog/install.yaml`),
a service in `argocd-notifications-cm` (e.g. `service.slack: { token: $slack-token }`) and its token in
`argocd-notifications-secret`, then subscriptions in `env.yaml`:

```yaml
notifications:
  subscriptions:
    notifications.argoproj.io/subscribe.on-sync-failed.slack: northline-deploys
    notifications.argoproj.io/subscribe.on-health-degraded.slack: northline-deploys
    notifications.argoproj.io/subscribe.on-deployed.slack: northline-deploys
```

They are added to every Application of the environment. Not configured anywhere yet.

## Checks (no cluster)

```sh
deploy/argocd/validate.sh
```

For dev/staging/prod × aws/gcp/azure (and local × kind): `helm lint` + `helm template` of the app of apps |
`kubeconform -strict` against the Argo CD CRD schemas; the policy (only dev and local sync automatically, staging/prod
refuse `sync.automated`, no wildcard destinations, a GitLab `repoURL` works); the northline chart rendered with exactly
the value files each Application lists (Terraform stand-ins from `deploy/helm/test-values/` while `infra.yaml` is
empty) — first with the committed `images.yaml` (must refuse while digests are empty), then with a promoted one
(`test-values/promoted-images.yaml`; every image must carry a digest) | `kubeconform -strict`; and
`kubectl kustomize deploy/argocd/install` | `kubeconform`. CI, manual only: GitHub **Actions › gitops ›
action=validate**; GitLab `PIPELINE_PART=gitops` (also part of `all`).

## Local rehearsal (kind)

`deploy/argocd/kind/rehearse.sh up` runs the whole flow on a laptop: a kind cluster (`northline-gitops`), the
Postgres/Valkey stand-ins of `deploy/kind/up.sh` (without Helm), Argo CD from `deploy/argocd/install`, and a bare Git
repository holding a commit of your working tree, mounted into the node and read by argocd-repo-server as
`file:///gitops/northline.git` — the stand-in for GitHub/GitLab. `envs/local` syncs automatically with
`values-local-kind.yaml` (api, auth, bff). `rehearse.sh push` commits the working tree again — the equivalent of
merging a PR; `rehearse.sh down` removes everything.

```sh
cd server && ./gradlew :api:jibDockerBuild :auth:jibDockerBuild :bff:jibDockerBuild -Pimage.platforms=linux/amd64 && cd ..
deploy/argocd/kind/rehearse.sh up          # IMAGE_TAG=dev by default
kubectl -n argocd get applications         # northline-local-root, northline-local: Synced / Healthy
deploy/argocd/kind/rehearse.sh down
```

Where quay.io (Argo CD) or public.ecr.aws (its Redis) are unreachable, set `ARGOCD_IMAGE` / `ARGOCD_REDIS_IMAGE` to
reachable copies of the same release; the image must have upstream's layout (`/usr/local/bin/argocd*`, uid 999).

**Rehearsed 2026-09-30** (kind 0.30, Kubernetes 1.34, Argo CD 3.1.1 — the Bitnami build of that release re-laid
out as upstream's, because quay.io is blocked on the rehearsal machine; Redis from Docker Hub; a kind node image with a
runc wrapper, because that machine's Docker lacks `CAP_SYS_RESOURCE` — `KIND_NODE_IMAGE`):

- bootstrap → `northline-local-root` and `northline-local` Synced/Healthy by themselves: PreSync migration Job (41
  migrations, categories seeded) completed at 03:18:06, the Deployments' ReplicaSets were created at 03:18:08, the
  PostSync OAuth client Job ran last (`studio-bff: up to date`);
- `sync.automated: false` pushed to Git → the root rewrote the child Application (no automated policy, no finalizer);
  a following change (bff 2 replicas) left `northline-local` **OutOfSync** with nothing applied until a manual sync;
- a broken database URL, synced by hand → the PreSync Job failed (`database "missing_db" does not exist`), the sync
  ended *Failed*, and no Deployment, ReplicaSet or ConfigMap changed (resource versions compared); re-syncing the same
  content failed again at once; reverting the commit and syncing returned to Synced/Healthy without re-running the
  (unchanged) migration;
- the rehearsal found three problems, fixed before this was committed (DECISIONS § S-15): two sources from the same
  repository at different revisions are refused by Argo CD; `ApplyOutOfSyncOnly` skipped a changed Deployment on a
  sync started right after a spec change; and same-named hook objects raced with `BeforeHookCreation` under
  server-side apply (a stale finished migration Job counted as the new one) — hence the content-hashed hook names.
