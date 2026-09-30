# Token signing keys — set-up and rotation (S-7)

northline-auth signs every token it issues — access tokens, ID tokens and the finance step-up proofs — with one ES256
(EC P-256) key. The **active** key signs. `https://auth.<domain>/oauth2/jwks` publishes the active key, a **next** key
waiting to take over, and **retiring** keys that are kept only so the tokens they signed still verify. The `kid` of
every token is the RFC 7638 thumbprint of its key, so every replica and every environment names a key the same way.

Who reads the JWK set: the api (resource server via `AUTH_ISSUER` discovery, and `JwtStepUpVerifier` for payouts), the
studio-bff (`AUTH_INTERNAL_URL/oauth2/jwks`, ID tokens) and northline-auth itself (userinfo). They cache it for up to
5 minutes and fetch it again when a token has a `kid` they don't know. Refresh tokens are opaque (stored in
`auth.oauth2_authorization`), so a rotation never signs anyone out.

Code: `server/auth/src/main/java/ca/northline/auth/signing` (`SigningKeys` port, `LocalFileSigningKeys`,
`RemoteSigningKeys` + `AwsKmsKeyService` / `GcpKmsKeyService` / `AzureKeyVaultKeyService`, `KeyStoreJwtEncoder`).

## Choosing the provider

| `KMS_PROVIDER` | where the private key is | who signs | allowed under | `KMS_KEY_ID` |
|---|---|---|---|---|
| `local` (default) | JWK set file `signing-keys.jwks.json` (mode 600) in `SIGNING_KEYS_DIR` (default `~/.northline/auth-signing-keys`), created on first start | northline-auth | `local`, `test`, `dev` (with a volume shared by the replicas) | — |
| `aws` | AWS KMS, key spec `ECC_NIST_P256`, usage `SIGN_VERIFY` | KMS (`kms:Sign`, `ECDSA_SHA_256` on the digest) | every profile | key ARN (or alias ARN — prefer the key ARN, see rotation) |
| `gcp` | Cloud KMS, algorithm `EC_SIGN_P256_SHA256`, purpose `ASYMMETRIC_SIGN` (protection level HSM recommended) | Cloud KMS (`asymmetricSign`) | every profile | the key **version** resource name `projects/…/locations/…/keyRings/…/cryptoKeys/…/cryptoKeyVersions/N` |
| `azure` | Azure Key Vault (or Managed HSM), key type `EC-HSM` (Premium vault) or `EC`, curve `P-256` | Key Vault (`sign`, `ES256`) | every profile | the key identifier URL **with** its version `https://<vault>.vault.azure.net/keys/<name>/<version>` |

- Under `staging` and `prod` the app refuses to start without `KMS_PROVIDER` and `KMS_KEY_ID`
  (S-1 required-environment check: purposes `signing-provider` and `signing-key`) and refuses `KMS_PROVIDER=local`.
  `dev` only requires `KMS_PROVIDER`.
- Cloud providers fetch the public keys of `KMS_KEY_ID` and `KMS_PUBLISHED_KEY_IDS` at start-up: a wrong id, a key of
  the wrong type or a missing permission stops the start-up with the reason, never the first sign-in.
- Credentials are never variables of ours: AWS SDK default chain (IRSA / EKS Pod Identity), Google Application
  Default Credentials (Workload Identity), Azure `DefaultAzureCredential` (AKS workload identity).
- Only the chosen provider's client is created; the three SDKs ship in the jar either way.

| variable | default | meaning |
|---|---|---|
| `KMS_PROVIDER` | `local` | `local` · `aws` · `gcp` · `azure` |
| `KMS_KEY_ID` | — | the key that signs (cloud providers) |
| `KMS_PUBLISHED_KEY_IDS` | empty | comma-separated keys published without signing: the next key before a switch, the previous one after it |
| `KMS_REGION` | SDK default (`AWS_REGION`) | AWS only |
| `KMS_ENDPOINT` | provider endpoint | AWS only (LocalStack, VPC endpoint URL) |
| `SIGNING_KEYS_DIR` | `~/.northline/auth-signing-keys` | `local` only: directory of the key file; every replica must mount the same one |
| `SIGNING_KEYS_ROTATE_EVERY` | empty (off) | `local` only: rotate automatically when the newest key is this old, e.g. `90d` (checked hourly under the file lock, so N replicas rotate once) |

### Creating the key (until Terraform does it — S-2)

**With Terraform (S-2):** `infra/terraform/envs/<cloud>/<env>` creates the `signing` key (HSM-backed in prod on
Google Cloud and Azure), grants only the `northline-auth` workload identity sign + get-public-key on it, and writes
`KMS_PROVIDER`, `KMS_KEY_ID` (AWS key ARN · Google Cloud key version `…/cryptoKeyVersions/1` · Azure versioned key URL)
and `KMS_PUBLISHED_KEY_IDS` into `config_env`; rotations set them through `signing_key_ids`
([infrastructure.md § 4](infrastructure.md#signing-key-rotation-with-terraform-s-7)). The commands below are for an
environment Terraform doesn't manage, and for the new key of an AWS rotation.

Always in the environment's Canadian region. The workload identity of northline-auth needs **sign** and **read the
public key** on this key only.

```sh
# AWS — policy: kms:Sign, kms:GetPublicKey on the key ARN
aws kms create-key --region ca-central-1 --key-spec ECC_NIST_P256 --key-usage SIGN_VERIFY \
  --description "northline-auth token signing (prod)" --query KeyMetadata.Arn --output text

# Google Cloud — role roles/cloudkms.signerVerifier (+ roles/cloudkms.publicKeyViewer) on the key
gcloud kms keyrings create northline-auth --location northamerica-northeast1
gcloud kms keys create token-signing --keyring northline-auth --location northamerica-northeast1 \
  --purpose asymmetric-signing --default-algorithm ec-sign-p256-sha256 --protection-level hsm
gcloud kms keys versions list --key token-signing --keyring northline-auth \
  --location northamerica-northeast1 --format='value(name)'            # → KMS_KEY_ID

# Azure — role "Key Vault Crypto User" on the key (or vault)
az keyvault key create --vault-name <vault> --name token-signing --kty EC-HSM --curve P-256 --ops sign verify \
  --query key.kid --output tsv                                         # → KMS_KEY_ID (versioned URL)
```

Check after a deploy: `curl -s https://auth.<domain>/oauth2/jwks | jq '.keys[].kid'` lists the expected kids, and the
auth log has `Signing keys: provider=… active=<kid> (…) published=[…]`.

## Rotating — local provider

The command edits the key file; running servers notice the change (file time, re-read at the latest every 30 s). No
restart. From `server/`:

```sh
./gradlew :auth:signingKeys --args='status'
./gradlew :auth:signingKeys --args='rotate'        # new key published now, signs 10 min later; old key kept 1 h more
# other directory or timings:
./gradlew :auth:signingKeys --args='rotate --dir=/var/lib/northline/keys --publish-ahead=10m --retire-after=1h'
# from a built jar (e.g. in a dev pod that mounts SIGNING_KEYS_DIR):
java -cp northline-auth.jar -Dloader.main=ca.northline.auth.signing.SigningKeysCommand \
  org.springframework.boot.loader.launch.PropertiesLauncher rotate
```

What `rotate` does: adds a key with `nbf = now + publish-ahead` (published at once, signing from `nbf`) and gives the
keys signing until then `exp = nbf + retire-after`. Keys past `exp` disappear from the JWK set and from the file at the
next rotation. `publish-ahead` must exceed the JWK set caches (5 min); `retire-after` must exceed the longest token
lifetime (ID tokens 30 min, access tokens 10 min, step-up proofs 5 min). Or set `SIGNING_KEYS_ROTATE_EVERY=90d`.

Losing the file only costs the tokens in flight: a new key is generated, access tokens already issued (≤ 10 min)
fail at the api until the BFF refreshes them, while refresh tokens and sessions survive (they are not signed).

## Rotating — cloud providers

A new key (AWS) or key version (GCP, Azure) plus two configuration changes. Each step is a normal rolling deploy of
northline-auth; old and new replicas stay compatible at every step because each step only *adds* to what is published
before anything signs with it.

1. **Create** the new key / version (commands above; GCP: `gcloud kms keys versions create --key token-signing …`;
   Azure: `az keyvault key rotate` or `key create` again → new versioned URL). Grant the same permissions.
2. **Publish it**: `KMS_PUBLISHED_KEY_IDS=<new id>` (keep `KMS_KEY_ID=<old id>`). Deploy. Check the JWK set lists both
   kids. **Wait at least 5 minutes** (JWK set caches).
3. **Switch**: `KMS_KEY_ID=<new id>`, `KMS_PUBLISHED_KEY_IDS=<old id>`. Deploy. New tokens carry the new kid; tokens
   signed by the old key still verify.
4. **Retire**: after at least 1 hour (longest token lifetime + slack), `KMS_PUBLISHED_KEY_IDS=` (empty). Deploy.
   Then disable the old key/version in the KMS (AWS `disable-key` + `schedule-key-deletion` after a grace period;
   GCP `versions disable`/`destroy`; Azure disable the version).

Rotate at least yearly and whenever someone who could use the key leaves. Don't use AWS aliases as `KMS_KEY_ID`
while rotating: re-pointing an alias switches every replica at a random moment instead of step 3.

## Compromised key (emergency)

Tokens signed by the compromised key must stop being accepted at once. Refresh tokens and sessions are not signed with
it, so people stay signed in; calls with an old access token fail until the BFF refreshes it.

- **local**: `./gradlew :auth:signingKeys --args='rotate --immediately'`, then
  `./gradlew :auth:signingKeys --args='retire <compromised kid>'`.
- **cloud**: create a new key, set `KMS_KEY_ID=<new>` and `KMS_PUBLISHED_KEY_IDS=` (empty) in one deploy, then disable
  the compromised key in the KMS. Until JWK set caches expire (≤ 5 min) the api may still accept old tokens: restart the
  api and the bff to drop their caches at once.
- If the sessions themselves may be compromised too, revoke them: delete the rows of `auth.oauth2_authorization`
  (refresh tokens) and flush the `nl:auth:*` and `nl:studio-bff:*` sessions in Valkey (everyone signs in again).

## Troubleshooting

| symptom | cause / fix |
|---|---|
| `KMS_PROVIDER=local is not allowed under staging/prod` | set `KMS_PROVIDER` to `aws`, `gcp` or `azure` and `KMS_KEY_ID` |
| `APPLICATION FAILED TO START … signing-provider: KMS_PROVIDER` / `signing-key: KMS_KEY_ID` | the variables are missing (S-1 check) |
| `KMS key … is RSA_2048, not ECC_NIST_P256` (or the GCP/Azure equivalent) | wrong key type: create an EC P-256 signing key |
| AccessDenied / PERMISSION_DENIED / 403 at start-up | the workload identity lacks sign / get-public-key on that key |
| api answers 401 `invalid_token` right after a switch | step 2 was skipped or shorter than 5 min: publish first, then switch |
| `No usable signing key in …/signing-keys.jwks.json` | every key in the file has expired (clock jump, manual edit): `rotate --immediately` |
