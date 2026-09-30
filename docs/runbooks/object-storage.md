# Object storage runbook (S-10)

Uploads (listing images, onboarding documents and logos, message attachments, kitchen photos, job/quote photos,
dispute evidence) go to one private bucket (S3, Google Cloud Storage) or container (Azure Blob) per environment.
The api picks the adapter from `STORAGE_PROVIDER`; nothing else changes between clouds.

| `STORAGE_PROVIDER` | where the bytes go | allowed under |
|---|---|---|
| `local` (default) | each module's folder in the temp directory (unchanged disk fakes) | `local`, `test`; `dev` starts but uploads fail loudly; **refused** under `staging`/`prod` |
| `s3` | AWS S3, or any S3-compatible server (RustFS, MinIO) through `STORAGE_ENDPOINT` + `STORAGE_PATH_STYLE=true` | every profile |
| `gcs` | Google Cloud Storage | every profile |
| `azure` | Azure Blob Storage | every profile |

## How it works

- `ca.northline.shared.storage.ObjectStore` (put / get / info / exists / delete / presigned GET) has one adapter per
  provider. Each module keeps its own port (`DocumentStorage`, `AttachmentStorage`, `KitchenPhotoStore`,
  `MediaStorage`, `MediaStore`, `DisputeEvidenceStorage`) and implements it over the store.
- **Keys:** `<module>/<merchantId>/<ulid>.<ext>` — e.g. `merchants/01J9…PWM1/01K2….pdf`. The database keeps the part
  after `<module>/`. One prefix per merchant, so retention (S-107) and erasure (S-105) can work on
  `<module>/<merchantId>/`. Uploads made before S-10 under `local` keep their old keys (`listings/…`, `messages/…`,
  `disputes/…`); they only exist on developers' disks.

  | module | prefix | written by | read by (auth check) |
  |---|---|---|---|
  | merchants | `merchants/` | `POST /api/v1/merchants/{id}/onboarding/documents` (legal, verification, logo; settings renewals) | `GET …/onboarding/documents/{doc}` (member, VIEW); public `GET /api/v1/storefronts/{slug}/logo` (published storefronts only) |
  | catalogue | `catalogue/` | `POST /api/v1/merchants/{id}/media` | `GET …/media/{mediaId}` (member, VIEW) |
  | food | `food/` | `POST …/menu-items/{id}/photo` | `GET …/menu-items/{id}/photo` (member, VIEW) |
  | messaging | `messaging/` | `POST …/message-attachments` | `GET …/message-attachments/{id}` (member, VIEW) |
  | booking | `booking/` | `POST …/jobs/media` | — (no download endpoint yet) |
  | payments | `payments/` | `POST …/disputes/{id}/evidence` | `GET …/disputes/{id}/evidence/{evidenceId}` (member, VIEW) |

- **Checks before storing** stay in the modules (type, file signature, size, image dimensions). Then every upload
  passes the **virus-scan hook** (`VirusScanner`): the default accepts everything; declaring a `VirusScanner` bean
  (ClamAV, a cloud malware scanner) turns scanning on. An infected file gets `422` on `file`
  ("This file can't be accepted. Try a different file.") and is not stored; a scanner error fails the upload.
  Under `STORAGE_PROVIDER=local` the disk fakes do not call it.
- **Downloads are streamed through the api**, after the endpoint's merchant check. Presigned GET URLs (≤ 1 h) exist in
  `ObjectStore.presignGet` for later use (consumer app, CDN) but no endpoint hands them out yet; there are no
  presigned uploads (they would bypass the checks above). So **no bucket CORS rules are needed**.
- **Encryption at rest:** always on. `STORAGE_ENCRYPTION_KEY` empty = the provider default (S3 SSE-S3, Google-managed,
  Microsoft-managed keys). Set it for a customer-managed key: AWS KMS key ARN/alias (SSE-KMS), Cloud KMS key name
  (CMEK), or an Azure encryption scope name. In transit: HTTPS to the provider endpoints.
- Content type and size are stored with the object (and in the module's table). Objects are never public.

## Variables

| variable | example | notes |
|---|---|---|
| `STORAGE_PROVIDER` | `s3` / `gcs` / `azure` | **required** under staging/prod (`local` refused) |
| `STORAGE_BUCKET` | `northline-prod-uploads` | bucket (S3, GCS) or container (Azure); **required** under staging/prod |
| `STORAGE_REGION` | `ca-central-1` | S3 only (signing region) |
| `STORAGE_ENDPOINT` | empty · `http://localhost:9100` · `https://nlproduploads.blob.core.windows.net` | S3: empty for AWS, the server URL for RustFS/MinIO. **Azure: the account URL (required).** GCS: empty (set only for an emulator — then no credentials are sent) |
| `STORAGE_PATH_STYLE` | `false` · `true` | `true` for RustFS/MinIO |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | empty in the cloud | S3-compatible servers: static keys. Azure: account name + account key (Azurite only). Empty = workload identity |
| `STORAGE_ENCRYPTION_KEY` | `arn:aws:kms:ca-central-1:…:key/…` · `projects/…/cryptoKeys/uploads` · `cmk-uploads` | optional customer-managed key (see above) |

Credentials in the cloud come from the pod's identity: EKS Pod Identity or IRSA (AWS default credential chain), GKE
Workload Identity (Application Default Credentials), AKS Workload Identity (`DefaultAzureCredential`). The api logs
`Object storage: <provider> bucket=… ` at start-up.

## Local (RustFS)

```sh
docker compose --profile storage up -d    # RustFS: S3 API http://localhost:9100, console http://localhost:9101
                                          # the one-shot storage-bucket service creates northline-local
```

Then in `server/.env` (the values match the compose defaults and `server/.env.example`):

```sh
STORAGE_PROVIDER=s3
STORAGE_BUCKET=northline-local
STORAGE_REGION=ca-central-1
STORAGE_ENDPOINT=http://localhost:9100
STORAGE_ACCESS_KEY=northline
STORAGE_SECRET_KEY=northline-dev-secret
STORAGE_PATH_STYLE=true
```

Start the api (`./gradlew :api:bootRun --args='--spring.profiles.active=local'`) and upload through the Studio or:

```sh
M=01J9ZD3V00000000000000PWM1
curl -s -H 'X-Dev-User: 01J9ZD3V00000000000000RAV1' -F purpose=verification \
  -F 'file=@some.pdf;type=application/pdf' localhost:8080/api/v1/merchants/$M/onboarding/documents
aws --endpoint-url http://localhost:9100 s3 ls s3://northline-local/merchants/$M/   # AWS_ACCESS_KEY_ID=northline …
```

Seeded kitchen items reference `seed/*.jpg` photos that only the `local` fake bundles, so under `s3` they show the
placeholder tile. Tests start RustFS, fake-gcs-server and Azurite themselves (Testcontainers). Ports taken? Change
`STORAGE_PORT` / `STORAGE_CONSOLE_PORT` in the root `.env` and `STORAGE_ENDPOINT` to match.

## Cloud set-up (until Terraform does it — S-2)

**With Terraform (S-2):** `infra/terraform/envs/<cloud>/<env>` creates the bucket (container `uploads` on Azure) with
the settings below — private, TLS only, versioning, noncurrent versions expire after 30 days, incomplete multipart
uploads aborted after 7 days, customer-managed `data` key — grants the `northline-api` workload identity exactly the
least-privilege access listed per cloud, and writes `STORAGE_PROVIDER`, `STORAGE_BUCKET`, `STORAGE_REGION`,
`STORAGE_ENDPOINT` and `STORAGE_ENCRYPTION_KEY` into `config_env`
([infrastructure.md § 4](infrastructure.md#4-outputs--the-apps-environment-variables)). Differences from the manual
set-up: Terraform names the buckets `northline-<env>-uploads` and the Azure account `nl<env>st<suffix>`, and on Azure the
customer-managed key is set on the whole account (so `STORAGE_ENCRYPTION_KEY` stays empty, no encryption scope). The
tables below are for an environment Terraform doesn't manage.

One bucket per environment in the environment's Canadian region, private, nothing public. Names below use `<env>` =
`dev` | `staging` | `prod`.

### AWS (Amazon S3)

| item | setting |
|---|---|
| bucket | `northline-<env>-uploads` in `ca-central-1`; Block Public Access on (all four); Object Ownership "bucket owner enforced" (ACLs off) |
| encryption | default SSE-S3; or SSE-KMS with a customer-managed key + S3 Bucket Key, and `STORAGE_ENCRYPTION_KEY` = that key ARN |
| bucket policy | deny `aws:SecureTransport = false` |
| versioning | on (recovery from accidental deletes/overwrites) |
| lifecycle | noncurrent versions expire after 30 days; abort incomplete multipart uploads after 7 days; **no expiry of current objects** (retention rules are S-107) |
| identity | EKS Pod Identity (or IRSA) role for the api's service account |
| env | `STORAGE_PROVIDER=s3`, `STORAGE_BUCKET`, `STORAGE_REGION=ca-central-1`; no endpoint, no keys |

Least-privilege policy for the api role (`s3:ListBucket` is what makes a missing key answer 404 instead of 403):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::northline-<env>-uploads/*" },
    { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::northline-<env>-uploads" },
    { "Effect": "Allow", "Action": ["kms:GenerateDataKey", "kms:Decrypt"],
      "Resource": "arn:aws:kms:ca-central-1:<account>:key/<uploads-key-id>" }
  ]
}
```

(the KMS statement only with SSE-KMS).

### Google Cloud (Cloud Storage)

| item | setting |
|---|---|
| bucket | `northline-<env>-uploads`, location `northamerica-northeast1` (single region), Standard class; uniform bucket-level access; public access prevention enforced |
| encryption | Google-managed; or CMEK: a Cloud KMS key in the same region, grant `roles/cloudkms.cryptoKeyEncrypterDecrypter` to the Cloud Storage service agent (`service-<project-number>@gs-project-accounts.iam.gserviceaccount.com`), set it as the bucket default key and as `STORAGE_ENCRYPTION_KEY` (`projects/…/locations/northamerica-northeast1/keyRings/…/cryptoKeys/…`) |
| recovery | soft delete (default 7 days) on; object versioning optional (then a lifecycle rule deleting noncurrent versions after 30 days) |
| lifecycle | no deletion of live objects (S-107) |
| identity | GKE Workload Identity: the api's Kubernetes service account → Google service account `northline-api-<env>` |
| IAM | on the **bucket**: `roles/storage.objectUser` for the service account (create/get/delete/list objects, no bucket admin). For presigned URLs (not used yet): `roles/iam.serviceAccountTokenCreator` for the service account on itself (the SDK signs through IAM `signBlob`) |
| env | `STORAGE_PROVIDER=gcs`, `STORAGE_BUCKET`; no endpoint, no keys |

### Azure (Blob Storage)

| item | setting |
|---|---|
| storage account | `nl<env>uploads` (StorageV2, Standard LRS or ZRS) in `canadacentral`; "Allow Blob anonymous access" off; minimum TLS 1.2; secure transfer required; **shared key access disabled** (the api uses Entra ID) |
| container | `uploads`, private access level |
| encryption | Microsoft-managed; or a customer-managed key: an **encryption scope** backed by a Key Vault key (`az storage account encryption-scope create --key-source Microsoft.KeyVault …`), `STORAGE_ENCRYPTION_KEY` = the scope name |
| recovery | blob soft delete 30 days, container soft delete 7 days; versioning optional |
| lifecycle | no deletion of live blobs (S-107) |
| identity | AKS Workload Identity: user-assigned managed identity federated with the api's Kubernetes service account (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE` are injected by the webhook) |
| RBAC | "Storage Blob Data Contributor" scoped to the **container**. For presigned URLs (not used yet): "Storage Blob Delegator" on the account (user-delegation SAS) |
| env | `STORAGE_PROVIDER=azure`, `STORAGE_BUCKET=uploads`, `STORAGE_ENDPOINT=https://nl<env>uploads.blob.core.windows.net`; no keys |

## Checks after a deploy

1. Start-up log: `Providers: storage=s3 …` and `Object storage: s3 bucket=northline-<env>-uploads …`.
2. Upload a document in the Studio (Settings › Stripe & compliance, or onboarding) and open it again.
3. The object is at `merchants/<merchantId>/<documentId>.pdf` in the bucket, with its content type.

## Troubleshooting

| symptom | cause |
|---|---|
| `STORAGE_PROVIDER=local is not allowed under staging/prod` | set `STORAGE_PROVIDER` and `STORAGE_BUCKET` |
| `STORAGE_ENDPOINT (the account URL …) is required` | Azure needs the account URL |
| S3 `403 AccessDenied` reading a document that was never uploaded | the role lacks `s3:ListBucket` (S3 hides 404s without it) |
| S3 `403` on upload with SSE-KMS | the role lacks `kms:GenerateDataKey` on the key |
| GCS `403 … storage.objects.delete` when re-uploading a kitchen photo | overwriting needs delete: use `roles/storage.objectUser`, not `objectCreator` |
| Azure `AuthorizationPermissionMismatch` | the managed identity lacks "Storage Blob Data Contributor" on the container (role assignments take a few minutes) |
| `XAmzContentSHA256Mismatch` or checksum errors against an S3-compatible server | set `STORAGE_ENDPOINT` (with an endpoint the SDK only sends checksums where required) |
| uploads rejected with "This file can't be accepted" | the virus scanner flagged it (the api logs the threat name at WARN) |
