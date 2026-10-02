# Secrets runbook — External Secrets on AWS, Google Cloud and Azure (S-6)

Every secret the apps read (database and cache passwords, Kafka and Elasticsearch credentials, Stripe keys and webhook
secrets, OAuth client secrets and hashes, the TOTP and webhook encryption keys, the email and SMS provider keys) lives
in the environment's **cloud secrets manager** and nowhere else. External Secrets Operator (ESO) copies them into
Kubernetes Secrets that the pods read as environment variables. Nothing secret is in the repository, in Helm values or
in Terraform variables; `.env` files are for local development only.

> **Status (2026-09-30):** the chart's External Secrets resources are rendered and schema-checked for all three clouds,
> and the whole path (SecretStore → ExternalSecret → Secret → pods, plus a rotation) was exercised on kind with ESO's
> `fake` provider. No cloud environment exists yet, so the AWS / Google Cloud / Azure stores have not talked to a real
> secrets manager.

Other runbooks: [deploy](deploy.md) · [infrastructure](infrastructure.md) · [dev](dev.md) · [staging](staging.md) ·
[prod](prod.md) · [key rotation (token signing)](key-rotation.md)

## How it fits together

```
Terraform (infra/terraform)                 Helm chart (deploy/helm/northline)                 pods
  secrets module: one secret per name  ──►   SecretStore "northline"  (provider aws|gcp|azure)
  data stores: generated passwords           ExternalSecret northline-<app>  ──► Secret northline-<app>-secrets ──► env
  workload identity "external-secrets"       (only the variables that app reads)
  output helm_values.externalSecrets
```

| | AWS | Google Cloud | Azure |
|---|---|---|---|
| secrets manager | Secrets Manager, `ca-central-1` | Secret Manager, user-managed replication in the region | Key Vault (one vault per environment, `nl-<env>-sec-…`) |
| secret names | `northline/<env>/<name>` | `northline-<env>-<name>` | `<name>` |
| ESO store (`externalSecrets.provider`) | `aws` → `SecretsManager` | `gcp` → `gcpsm` (`projectID`) | `azure` → `azurekv` (`vaultUrl`, `authType: WorkloadIdentity`) |
| how ESO authenticates | IRSA role of the ESO controller ServiceAccount `external-secrets/external-secrets` (read on `northline/<env>/*`) | GKE Workload Identity of the same ServiceAccount (Secret Manager accessor on `northline-<env>-*`) | Azure Workload Identity of the same ServiceAccount ("Key Vault Secrets User" on the vault) |
| static keys | none | none | none |

Terraform creates the controller's cloud identity and its read grant (`workload_identities["external-secrets"]`,
[infra/terraform/README.md § Workload identities](../../infra/terraform/README.md#workload-identities)); the
SecretStore in the chart authenticates **as the ESO controller** (no `auth` block), so the app namespaces need no cloud
identity of their own for secrets.

Each app gets its **own** Secret holding only its variables (`apps.<app>.secretEnv` in the chart): the bff never sees
the database password, the worker never sees Stripe keys. Required variables are always mapped; an optional one is
mapped only when listed in `externalSecrets.optionalKeys`, because an ExternalSecret fails **as a whole** when any
remote secret it names is missing or has no value (empty placeholders created by Terraform included).

## Secret inventory

`remote name` is the `<name>` part above. **Generated** = Terraform writes the value; **operator** = created empty by
Terraform (AWS, Google Cloud) or not at all (Azure: Key Vault has no empty secrets), the operator sets the value.

| variable | remote name | apps | required | value from |
|---|---|---|---|---|
| `DB_PASSWORD` | `db-app-password` | api, auth, worker | yes | generated (S-3) |
| `REDIS_PASSWORD` | `redis-password` | api, auth, bff, worker | AWS / Azure (listed in `optionalKeys`); **none** on Google Cloud (Memorystore has no password) | generated (S-3) |
| `KAFKA_SASL_JAAS_CONFIG` | `kafka-sasl-jaas-config` | api, auth (S-28), worker | with SASL (always in the cloud; `optionalKeys`) | generated (S-3) |
| `ES_PASSWORD` | `es-password` | api, worker | with Elastic Cloud (`optionalKeys`) | generated (S-3) |
| `TOTP_KEY` | `totp-key` | auth | yes | operator: `openssl rand -base64 32` |
| `WEBHOOK_SECRET_KEY` | `webhook-secret-key` | api, worker (S-33) | yes | operator: `openssl rand -base64 32` |
| `STUDIO_BFF_SECRET` | `studio-bff-secret` | bff | yes | operator: `openssl rand -base64 32` |
| `STUDIO_BFF_SECRET_HASH` | `studio-bff-secret-hash` | auth | yes | operator: `{bcrypt}` of `STUDIO_BFF_SECRET` ([dev.md](dev.md#environment-variables)) |
| `CONSUMER_BFF_SECRET` | `consumer-bff-secret` | consumer-bff (S-45) | yes | operator: `openssl rand -base64 32` |
| `CONSUMER_BFF_SECRET_HASH` | `consumer-bff-secret-hash` | auth | yes (S-45) | operator: `{bcrypt}` of `CONSUMER_BFF_SECRET` |
| `CONSOLE_BFF_SECRET` | `console-bff-secret` | console-bff (S-90) | yes | operator: `openssl rand -base64 32` |
| `CONSOLE_BFF_SECRET_HASH` | `console-bff-secret-hash` | auth | yes (S-90) | operator: `{bcrypt}` of `CONSOLE_BFF_SECRET` |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | `stripe-secret-key`, `stripe-publishable-key` | api | staging, prod | Stripe dashboard ([stripe.md](stripe.md)) |
| `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET` | `stripe-webhook-secret`, `stripe-connect-webhook-secret` | api | staging, prod | the two webhook endpoints' signing secrets ([stripe.md](stripe.md)) |
| `EMAIL_UNSUBSCRIBE_KEY` | `email-unsubscribe-key` | api | staging, prod | operator: `openssl rand -base64 32` ([email.md](email.md)) |
| `EMAIL_API_KEY` | `email-api-key` | api | with `sendgrid` (or `azure` with an access key) | provider console |
| `SMTP_PASSWORD` | `smtp-password` | api, worker | with `EMAIL_PROVIDER=smtp` | relay credentials |
| `GOOGLE_CLIENT_SECRET`, `APPLE_PRIVATE_KEY` | `google-client-secret`, `apple-private-key` | auth | staging, prod (S-18) | Google Cloud console / Apple Developer `.p8` key ([federation.md](federation.md)) |
| `REGISTRY_CORPORATIONS_CANADA_KEY`, `REGISTRY_ALBERTA_KEY`, `REGISTRY_CALGARY_APP_TOKEN` | `registry-corporations-canada-key`, `registry-alberta-key`, `registry-calgary-app-token` | api | with `api` / `opencorporates` / optional for `socrata` (S-23) | GC API Store subscription / OpenCorporates account / data.calgary.ca developer settings ([registries.md](registries.md)) |
| `OTEL_BACKEND_AUTH` | `otel-backend-auth` | otel-collector (S-111) | with an OTLP backend that needs credentials (`observability.collector.secretEnv`) | the backend's console, e.g. Grafana Cloud access policy token ([observability.md](observability.md#any-otlp-backend)) |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | `applicationinsights-connection-string` | otel-collector (S-111) | Azure | Application Insights resource in Canada Central ([observability.md](observability.md#azure)) |
| `GOOGLE_CALENDAR_CLIENT_SECRET`, `MICROSOFT_CALENDAR_CLIENT_SECRET` | `google-calendar-client-secret`, `microsoft-calendar-client-secret` | api | once that calendar provider is offered (S-32; add to `optionalKeys` when set) | Google Cloud console OAuth client / Microsoft Entra app registration › Certificates & secrets ([calendar-sync.md](calendar-sync.md)) |
| `SHOPIFY_CLIENT_SECRET`, `SQUARE_CLIENT_SECRET`, `SQUARE_WEBHOOK_SIGNATURE_KEY`, `LIGHTSPEED_CLIENT_SECRET` | `shopify-client-secret`, `square-client-secret`, `square-webhook-signature-key`, `lightspeed-client-secret` | api | once that platform is offered (S-35; add to `optionalKeys` when set) | Shopify Partner Dashboard app / Square Developer Console (application secret, webhook subscription signature key) / Lightspeed X-Series developer portal ([commerce-sync.md](commerce-sync.md)) |
| `CLOVER_CLIENT_SECRET`, `TOAST_CLIENT_SECRET` | `clover-client-secret`, `toast-client-secret` | api | once that POS is offered (S-36; add to `optionalKeys` when set) | Clover developer dashboard app / Toast partner credentials ([pos-menu-import.md](pos-menu-import.md)) |
| `GOOGLE_MAPS_API_KEY` | `google-maps-api-key` | api | staging, prod (S-47; dev only with `PLACES_PROVIDER=google` — then add to `optionalKeys`) | Google Cloud console › APIs & Services › Credentials, restricted to Places API (New) + Geocoding API ([google-maps.md](google-maps.md)) |
| `CONSOLE_HEALTH_PROMETHEUS_TOKEN` | `console-health-prometheus-token` | api | no — only for a metrics store that needs a bearer token (S-91; add to `optionalKeys` once set) | the store's read-only token (Grafana Cloud access policy with `metrics:read`, …) |
| `ONCALL_EXPORT_TOKEN` | `oncall-export-token` | api | no — empty keeps the on-call rota export off (S-113; add to `optionalKeys` once set) | any long random string (`openssl rand -base64 32`); give it to the paging tool that reads the rota ([alerting.md](alerting.md#the-on-call-rota)) |
| `ALERTING_PAGERDUTY_ROUTING_KEY` | `alerting-pagerduty-routing-key` | Alertmanager (ExternalSecret `northline-alerting`) | with `alerting.routing.*.provider=pagerduty` (S-113) | PagerDuty › Service › Integrations › Events API v2 integration key |
| `ALERTING_OPSGENIE_API_KEY` | `alerting-opsgenie-api-key` | Alertmanager | with `provider=opsgenie` | Opsgenie › Settings › Integrations › API integration key |
| `ALERTING_PAGE_WEBHOOK_URL`, `ALERTING_TICKET_WEBHOOK_URL` | `alerting-page-webhook-url`, `alerting-ticket-webhook-url` | Alertmanager | with `provider=webhook` for page / ticket | the receiver's Alertmanager-webhook URL (Grafana OnCall integration, a chat or ticketing bridge) |
| `OPENROUTER_API_KEY` | `openrouter-api-key` | api | no — until it exists AI features answer 503 (S-129; add to `optionalKeys` once set) | openrouter.ai › Settings › Keys, one key per environment with a credit limit ([ai.md](ai.md#set-up-per-environment)) |
| `SMS_AUTH_TOKEN` | `sms-auth-token` | auth | with `SMS_PROVIDER=twilio` | Twilio console ([README § SMS](README.md#sms-and-voice-codes-s-8)) |
| `PUSH_APNS_KEY`, `PUSH_FCM_SERVICE_ACCOUNT` | `push-apns-key`, `push-fcm-service-account` | worker | prod (S-102, `values-prod.yaml`); dev and staging once `PUSH_PROVIDER=native` (then set them to `true` in the overlay's `apps.worker.secretEnv`) | Apple Developer › Keys (an APNs key, the `.p8` file's PEM text) / Firebase console › Project settings › Service accounts (a JSON key) ([push.md § Set-up](push.md#set-up)) |

Which optional variables to map is a per-environment decision in the values, e.g. dev on AWS with Twilio and SendGrid:

```yaml
externalSecrets:
  optionalKeys: [REDIS_PASSWORD, KAFKA_SASL_JAAS_CONFIG, ES_PASSWORD, SMS_AUTH_TOKEN, EMAIL_API_KEY]
```

(values-staging/prod.yaml already make the Stripe and email keys required there.)

## Mobile release (CI and EAS)

The store release of the native apps (S-103, [mobile-release.md](mobile-release.md#secrets)) uses credentials that no
running app reads, so they are **not** in the cluster's secrets manager or the chart: they live in the CI system
(GitHub Actions secrets and variables / GitLab CI/CD variables, masked and protected) and in EAS (`eas credentials`,
`eas env:create`). Never commit them; examples in the repository use fake values.

| name | kind | holds | rotate |
|---|---|---|---|
| `EXPO_TOKEN` | CI secret | an Expo robot user's access token (builds, submit, update, metadata) | create a new token in Expo › Access tokens, update the secret, revoke the old |
| `ASC_API_KEY_P8` | CI secret (GitLab: File variable) | the App Store Connect API key's `.p8` text (role App Manager) — fastlane App Review, EAS Metadata | create a second key, update `ASC_API_KEY_P8` and `ASC_API_KEY_ID` (and the copy in `eas credentials -p ios`), revoke the old key |
| `ASC_API_KEY_ID`, `ASC_API_ISSUER_ID` | CI variables | the key's id and the team's issuer id (not secret) | with the key |
| `PLAY_SERVICE_ACCOUNT_JSON` | CI secret (GitLab: File variable) | the Google Play service account's JSON key — fastlane listing, promote, rollout, halt | add a key to the service account, update the secret and `eas credentials -p android`, delete the old key |
| `EAS_OWNER`, `EAS_CONSUMER_PROJECT_ID`, `EAS_COURIER_PROJECT_ID` | CI variables | the Expo organisation and projects (not secret) | — |
| `GOOGLE_SERVICES_JSON` | EAS file variable per environment (`development`, `preview`, `production`) | the app's Firebase config for FCM tokens (not a secret by Google's definition, but per environment and kept out of git) | replace the file variable; the next build uses it |
| signing (iOS distribution certificate and profiles, Android upload keystore) | EAS credentials (`credentialsSource: remote`) | — | EAS renews iOS certificates; back up the Android upload keystore in the secrets manager (`mobile-upload-keystore-<app>`, by hand); a lost upload key is reset through Play Console support |

## Setting it up (once per cluster and environment)

1. **Terraform** (`infra/terraform/envs/<cloud>/<env>`): applied as in [infrastructure.md](infrastructure.md). It
   creates the secrets, the ESO identity and its grant, and outputs the Helm values:

   ```sh
   terraform output -json helm_values > northline-<env>-values.json   # configEnv, workloadIdentities, externalSecrets
   terraform output -json kubernetes | jq '.workload_identities["external-secrets"]'   # for the ESO install below
   ```

2. **Set the operator secrets** (only names, never values, go through Terraform):

   ```sh
   # AWS
   aws secretsmanager put-secret-value --secret-id northline/<env>/totp-key --secret-string "$(openssl rand -base64 32)"
   # Google Cloud
   printf %s "$(openssl rand -base64 32)" | gcloud secrets versions add northline-<env>-totp-key --data-file=- --project <project>
   # Azure
   az keyvault secret set --vault-name <nl-<env>-sec-…> --name totp-key --value "$(openssl rand -base64 32)"
   ```

   Every required secret of the table needs a value before the apps can start (the ExternalSecret reports
   `SecretSyncedError` and names the missing secret otherwise).

3. **Install External Secrets Operator** (once per cluster, chart `external-secrets` ≥ 0.17, API `external-secrets.io/v1`).
   With Argo CD (S-15) it is the add-on `external-secrets-<env>`: put the identity below in
   `deploy/argocd/envs/<env>/addons/external-secrets.yaml` instead ([gitops.md](gitops.md)). By hand:

   ```sh
   helm repo add external-secrets https://charts.external-secrets.io
   helm upgrade --install external-secrets external-secrets/external-secrets -n external-secrets --create-namespace \
     --version 0.20.4 --set installCRDs=true \
     --set-json 'serviceAccount.annotations=<service_account_annotations from step 1>' \
     --set-json 'podLabels=<pod_labels from step 1>'        # Azure: {"azure.workload.identity/use":"true"}
   ```

   | cloud | annotation on `external-secrets/external-secrets` |
   |---|---|
   | AWS | `eks.amazonaws.com/role-arn: arn:aws:iam::<account>:role/northline-<env>-external-secrets` (or an EKS Pod Identity association instead) |
   | Google Cloud | `iam.gke.io/gcp-service-account: northline-<env>-external-secrets@<project>.iam.gserviceaccount.com` |
   | Azure | `azure.workload.identity/client-id: <client id>` + pod label `azure.workload.identity/use: "true"` |

   One ESO serves every environment namespace of the cluster. If one cluster ever hosts two environments, each needs
   its own identity: set `externalSecrets.<cloud>.auth` (AWS/GCP `jwt.serviceAccountRef`) or
   `externalSecrets.azure.serviceAccountRef` to a ServiceAccount in the environment's namespace instead.

4. **Install the chart** with the cloud overlay (it switches `externalSecrets.enabled` on) and the Terraform values —
   [deploy.md § Install](deploy.md#install-upgrade-roll-back):

   ```sh
   helm upgrade --install northline deploy/helm/northline -n northline-<env> \
     -f deploy/helm/northline/values-<env>.yaml -f deploy/helm/northline/values-<cloud>.yaml \
     -f northline-<env>-values.json --set global.image.registry=… --set global.image.tag=… --wait
   ```

5. **Check:** `kubectl -n northline-<env> get secretstore,externalsecret` → store `Valid`/`Ready`, every
   ExternalSecret `SecretSynced`; `kubectl -n northline-<env> get secret northline-api-secrets -o jsonpath='{.data}' | jq 'keys'`
   lists only the api's variables.

Without Terraform, the chart builds the remote names itself: `externalSecrets.remoteKeyPrefix` (`northline/{env}/`,
`northline-{env}-` or empty — set by the cloud overlays) + `externalSecrets.secretNames.<VAR>`; set
`externalSecrets.gcp.projectID` or `externalSecrets.azure.vaultUrl` by hand.

## Rotation

ESO re-reads the secrets manager every `externalSecrets.refreshInterval` (1 h) and updates the Kubernetes Secret. The
pods read their environment **at start**, so a rotation is always: new value in the secrets manager → Secret refreshed
→ restart the pods that use it. To skip the wait:

```sh
kubectl -n northline-<env> annotate externalsecret northline-<app> force-sync=$(date +%s) --overwrite
kubectl -n northline-<env> get secret northline-<app>-secrets -o jsonpath='{.metadata.resourceVersion}'   # changed
kubectl -n northline-<env> rollout restart deploy/northline-<app>
```

(Automatic restarts on Secret changes need a controller such as Stakater Reloader — not installed; add the
`secret.reloader.stakater.com/reload` pod annotation through `apps.<app>.podAnnotations` if you do.)

| secret | how to rotate | impact |
|---|---|---|
| `DB_PASSWORD` | set the new password in the secrets manager, `alter role northline_app password '…'` ([infrastructure.md § 5.1](infrastructure.md#51-postgresql-app-role-and-extensions-once-per-environment)), force-sync, restart api, auth, worker | connections opened with the old password keep working until the pools recycle; do it in a quiet window |
| `REDIS_PASSWORD` | ElastiCache: modify the AUTH token with `ROTATE` (old + new both valid), update the secret, force-sync, restart all four apps, then `SET` | none with `ROTATE`. Azure: regenerate the *secondary* key, switch the secret to it, restart, then regenerate the primary |
| `KAFKA_SASL_JAAS_CONFIG` | MSK: add a second SCRAM user, switch, remove the old; Event Hubs: switch to the secondary connection string, regenerate the primary; Managed Kafka: new service-account key, switch, delete the old | none if the old credential is removed only after the restart |
| `ES_PASSWORD` | reset in Elastic Cloud, update the secret, force-sync, restart api and worker | search/indexing fail between the reset and the restart |
| `STUDIO_BFF_SECRET` + `STUDIO_BFF_SECRET_HASH` | new secret and its bcrypt hash in the secrets manager, force-sync bff and auth, re-run the OAuth client sync (`helm upgrade` runs the Job again, or restart auth), restart the bff | sign-ins fail between the auth update and the bff restart (one secret per client, no overlap — [README § OAuth clients](README.md#oauth-clients-s-122)) |
| `STRIPE_SECRET_KEY` | roll the key in the Stripe dashboard (Stripe keeps the old one valid for the period you choose), update, restart api | none within the overlap |
| `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET` | "Roll secret" on the endpoint (Stripe signs with both for up to 24 h), update, restart api | none within the overlap |
| `TOTP_KEY` | **don't** without re-encrypting `auth.totp_secrets` — a new key breaks every authenticator enrolment | — |
| `WEBHOOK_SECRET_KEY` | **don't** without re-encrypting the stored partner webhook signing secrets | — |
| `EMAIL_UNSUBSCRIBE_KEY` | new value, restart api | links in emails already sent stop working (the page points to Settings) |
| `EMAIL_API_KEY`, `SMTP_PASSWORD`, `SMS_AUTH_TOKEN` | create the new credential at the provider, update, restart, revoke the old | none |
| `GOOGLE_CALENDAR_CLIENT_SECRET`, `MICROSOFT_CALENDAR_CLIENT_SECRET` | add a second client secret in the Google Cloud console / Entra app registration (both stay valid), update, restart api, delete the old one | none: stored refresh tokens belong to the client id, not the secret ([calendar-sync.md](calendar-sync.md#rotating)) |
| `OPENROUTER_API_KEY` | create a new key in openrouter.ai › Keys, update the secret, restart api, then delete the old key |
| `SHOPIFY_CLIENT_SECRET`, `LIGHTSPEED_CLIENT_SECRET`, `SQUARE_CLIENT_SECRET`, `SQUARE_WEBHOOK_SIGNATURE_KEY` | rotate in the platform's console, update, restart api | Shopify / Lightspeed webhooks signed with the old secret fail verification until the restart (the platforms retry); Square: replace the signature key the same way. Stored tokens stay valid ([commerce-sync.md](commerce-sync.md#operations)) |
| `CLOVER_CLIENT_SECRET`, `TOAST_CLIENT_SECRET` | rotate in the Clover dashboard / ask Toast for new partner credentials, update, restart api | none: stored Clover tokens stay valid; Toast tokens are fetched per api instance ([pos-menu-import.md](pos-menu-import.md#operations)) |
| `GOOGLE_MAPS_API_KEY` | create a second key with the same restrictions, update, restart api, delete the old key | none (both keys work until the old one is deleted) |
| `PUSH_APNS_KEY` | create a second APNs key in Apple Developer › Keys (two may exist), set it with its `PUSH_APNS_KEY_ID`, restart worker, revoke the old key | none (tokens are minted per worker; both keys are valid until the revoke) |
| `PUSH_FCM_SERVICE_ACCOUNT` | add a key to the service account in Google Cloud › IAM › Service accounts, update, restart worker, delete the old key | none |

Rehearsed on kind: the `STUDIO_BFF_SECRET` row end to end (new value → ESO refresh → OAuth client sync
`studio-bff: update secret` → bff restart → the token endpoint accepts the new secret and answers 401 to the old one).

## Local development and kind

- **Local (Gradle, Vite):** `server/.env` (git-ignored, from `server/.env.example`) — no secrets manager.
- **kind, plain Secret:** `values-local-kind.yaml` renders the Secret from values (`secrets.create`, refused unless
  `global.environment=local`).
- **kind, External Secrets:** install ESO in the kind cluster, then add `values-local-kind-eso.yaml`: a `fake` store
  holds the local values under AWS-style names (`northline/local/<name>`), and the chart's ExternalSecrets are the
  same as in the cloud. The `fake` provider is refused outside `global.environment=local`.

## Troubleshooting

| symptom | cause / fix |
|---|---|
| ExternalSecret `SecretSyncedError`, "secret not found" / "ResourceNotFoundException" | the remote secret doesn't exist or has **no value yet** (AWS/Google Cloud create them empty): set it, or drop an optional variable from `optionalKeys` |
| SecretStore not `Valid`: AccessDenied / PermissionDenied / 403 | the ESO controller ServiceAccount lacks the workload identity annotation (step 3), or Terraform's grant is missing; AWS: the role trusts `system:serviceaccount:external-secrets:external-secrets` |
| pod `CreateContainerConfigError` "secret northline-<app>-secrets not found" right after install | ESO hasn't synced yet (seconds); it resolves on its own — if not, see the ExternalSecret's status |
| a rotated value isn't picked up | pods only read env at start: force-sync, then `rollout restart` |
| `helm template` fails "no remote key for X" | a required variable has neither `remoteKeys.X` (Terraform) nor `secretNames.X` |
