# Backups, point-in-time restore and disaster recovery (S-114)

What protects each data store, how long it takes to come back (RTO) and how much can be lost (RPO), how to restore —
point in time, a backup, the copy in the other Canadian region, a masked prod copy into staging — and the drill that
proves it. The same tooling works on AWS, Google Cloud and Azure; every copy stays in Canada.

> **Nothing here has run against a real cloud.** No cloud account exists yet: the Terraform below is validated and
> planned offline against mocked providers only, and `scripts/dr/restore.sh` has only printed its `aws` / `gcloud` /
> `az` commands (`--dry-run`). What *has* run is the [local drill](#local-drill): a physical base backup with WAL
> archiving, a point-in-time restore, a dump restore and the masked prod → staging path, all verified, in
> PostgreSQL 17 + PostGIS containers. The first cloud drill (§ [Schedule](#roles-and-the-quarterly-drill)) turns the
> targets below into measured numbers.

## Contents

- [Targets: RPO and RTO](#targets-rpo-and-rto) — per data store, per scenario, per cloud
- [What Terraform sets up](#what-terraform-sets-up)
- [Restore procedures](#restore-procedures) — PITR, a backup, regional disaster, pointing an environment
- [Prod snapshot to staging](#prod-snapshot-to-staging) — masked on the way
- [Verifying a restore](#verifying-a-restore)
- [After a database restore](#after-a-database-restore) — search, Kafka, Valkey, object storage
- [Local drill](#local-drill) — and its recorded results
- [Roles and the quarterly drill](#roles-and-the-quarterly-drill)
- [Alerts](#alerts)
- [Known gaps](#known-gaps)

## Targets: RPO and RTO

**Postgres is the source of truth.** Everything else is either a copy of it (Elasticsearch, Kafka topics fed by the
outbox), ephemeral (Valkey), or files referenced from it (object storage). So the database gets the strongest
protection, and the others are rebuilt or replicated.

### Per data store (prod)

| data store | protection | scenario | RPO target | RTO target |
|---|---|---|---|---|
| **PostgreSQL** | HA standby in another zone | instance or zone failure | 0 (synchronous standby) | 5 min (automatic failover) |
| | automated backups + point-in-time recovery, 35 days | bad migration, bad deploy, deleted rows, corruption | 5 min | 2 h (restore to a new instance, verify, switch `DB_URL`) |
| | copy in the other Canadian region | loss of the primary region | 15 min (AWS, Google Cloud), 1 h (Azure) | 4 h (network + cluster in the secondary region, restore, switch) |
| **Object storage** (uploads) | versioning (30 days of older versions) | deleted or overwritten objects | 0 | 1 h (restore the version) |
| | replica in the other Canadian region (prod) | loss of the primary region | 15 min | 1 h (point `STORAGE_*` at the replica) |
| **Elasticsearch** | Elastic Cloud's managed snapshots (`found-snapshots`) | lost or corrupt index | n/a (derived) | full reindex from Postgres ([search.md](search.md#9-reindex-s-71)); snapshot restore as a shortcut |
| | none in the secondary region | loss of the region | n/a | a new deployment + full reindex |
| **Kafka** | not backed up | loss of the cluster or topics | events already handed to Kafka but not yet consumed (see [Kafka](#kafka-rebuilt-not-restored)) | topics re-created by the provisioning Job; the outbox re-publishes what was pending |
| **Valkey** | not backed up | loss of the cache | everything in it (see [Valkey](#valkey-not-backed-up)) | minutes: a new empty cache |
| **Secrets, KMS keys** | recovery windows (30 days in prod), deletion protection | deleted secret or key | 0 (recover it) | 1 h |
| **Terraform state** | versioned bucket (bootstrap) | corrupted or deleted state | 0 | 1 h (restore the previous version) |

dev and staging keep 7 days of point-in-time recovery in their own region and no cross-region copy: dev holds nothing
to recover, staging is re-created from a [masked prod copy](#prod-snapshot-to-staging).

### Per cloud (prod PostgreSQL)

| | AWS (RDS) | Google Cloud (Cloud SQL) | Azure (Flexible Server) |
|---|---|---|---|
| HA failover | Multi-AZ, ~1–2 min | `REGIONAL`, ~1 min | zone-redundant HA, ~1–2 min |
| PITR window | 35 days (`backup_retention_days`) | 7 days of transaction logs (Enterprise edition maximum); daily backups kept 35 | 35 days |
| PITR granularity (RPO) | ~5 min (logs shipped every 5 min; `LatestRestorableTime`) | seconds (logs continuously archived) | ~5 min (WAL backed up every 5 min) |
| copy in the other region | automated backups **replicated to ca-west-1** (or ca-central-1), encrypted with that region's key, 14 days, PITR there | **cross-region read replica** in northamerica-northeast2 (or -northeast1), own key, seconds behind | **geo-redundant backup** to the paired region canadaeast (or canadacentral) |
| regional RPO | ~15 min | seconds (replication lag) | < 1 h (Azure's geo-backup lag) |
| regional path | restore from the replicated backups into the secondary-region network | promote the replica | geo-restore into the paired region |
| backups when the instance is deleted | kept (`delete_automated_backups = false` in prod) + final snapshot | final backup not automatic; deletion protection on | kept for the retention period |

The regional RTO is dominated by standing the environment up in the secondary region (network, cluster, apps): the
stacks are identical in every Canadian region, so it is `terraform apply` of the prod env root with `region` set to
the secondary region, then the restore. That apply has never been timed; 4 h is the target to measure.

## What Terraform sets up

`infra/terraform` (offline-validated, [infrastructure.md](infrastructure.md)): the stack variable `backup` (env
roots: `backup = { cross_region = true }` in prod only) and the output `backup` (`terraform output -json backup`),
which `scripts/dr/restore.sh` reads.

| | AWS | Google Cloud | Azure |
|---|---|---|---|
| PostgreSQL backups + PITR | `backup_retention_period` 35/7 days, backup window 07:00 UTC | backups in the region, PITR, 35 backups / 7 days of logs | `backup_retention_days` 35/7 |
| database copy (prod) | `aws_db_instance_automated_backups_replication` → secondary region, key `northline-prod-backup-data` there | `google_sql_database_instance` `<name>-dr`, replica in the secondary region, CMEK key ring `northline-prod-backup` there | `geo_redundant_backup_enabled` (set at creation only: turning it on later replaces the server) |
| key for the copies | the **kms** module instantiated in the secondary region (a KMS key is regional) | the **kms** module instantiated in the secondary region; Cloud SQL and Cloud Storage service agents granted | none needed: Key Vault keeps a read-only copy of the vault in the paired region, so the existing `data` key encrypts the replica account |
| bucket versioning + lifecycle | versioned, older versions expire after 30 days, incomplete uploads after 7 | same | versioning + blob soft delete 30 days |
| bucket replica (prod) | S3 replication (+ delete markers, Replication Time Control 15 min, metrics) → `northline-prod-uploads-replica`; STANDARD_IA after 30 days, older versions expire after 30 (S-107: within the Privacy Policy's 35 days; was 90) | Storage Transfer Service event-driven replication job → `northline-prod-uploads-replica`; NEARLINE after 30 days, older versions after 30; deletes are **not** propagated (S-107 mismatch: [retention.md](retention.md#7-mismatches-between-the-policy-and-the-code)) | object replication → storage account `nlprodrp…` (LRS), same containers; Cool after 30 days, versions after 30; deletes not propagated (same) |
| Elasticsearch | Elastic Cloud deployment (S-3) — snapshots to its managed `found-snapshots` repository (`cloud-snapshot-policy`, every 30 min by default), in the deployment's own region | same | same |
| Kafka, Valkey | nothing (by design, below) | | |

Objects written before replication is switched on are not copied by S3 replication or the Storage Transfer job: after
the first apply in prod, run one S3 Batch Replication job / one Storage Transfer batch job (Azure's policy copies
existing blobs itself, `copy_blobs_created_after = "Everything"`).

### Elasticsearch: snapshots, but rebuilt rather than restored

The index is a read model (S-42/S-43): every document is derived from Postgres, and the full reindex with an alias
swap (S-71, `search-reindex` Job, [search.md § 9](search.md#9-reindex-s-71)) rebuilds it from scratch. So no
snapshot repository of our own is configured: Elastic Cloud already snapshots every deployment to its managed
`found-snapshots` repository (the ec provider configures snapshots only on ECE; on Elastic Cloud the
`elasticstack` provider would be needed for a custom repository, which adds nothing here). Restoring a snapshot is a
shortcut when it is younger than the database restore point; after any **database** restore, always reindex — the
index must match the restored database, not the lost one.

### Kafka: rebuilt, not restored

Topics hold events that are either transient (notifications, webhook triggers) or re-derivable (search projection).
The source of truth is Postgres, and the outbox lives there: `events.event_publication` (Spring Modulith) keeps every
event until it is published, and re-submits incomplete ones on start. Managed Kafka has no point-in-time backup on
any of the three clouds, and replaying a stale topic copy into a restored database would deliver events twice or out
of order. So Kafka is **not backed up**: after a loss, the provisioning Job re-creates the topics
([infrastructure.md § 5.3](infrastructure.md#53-kafka-topics-and-credentials)), the outbox re-publishes what was
pending, consumers dedupe (`events.processed_events`), the search reindex fixes the index, and webhook deliveries
and deferred notifications are rows in Postgres that are retried. **Lost:** events Kafka had accepted but no
consumer had processed — a notification may not be sent; the search index heals with the reindex.

### Valkey: not backed up

Valkey holds sessions and caches only. **Lost** with it: BFF sessions (everyone signs in again), caches (warm up
again), rate-limit counters (reset), Redis idempotency keys of the last 24 h (money-moving requests are also
recorded in `payments.idempotency_keys` in Postgres), booking slot holds (customers pick a slot again) and live
tracking positions (refreshed by the next courier update). Nothing in it is needed to recover. AWS ElastiCache keeps
snapshots as a side effect of its settings (7 days in prod); **do not restore them** — stale sessions and holds
are worse than none.

## Restore procedures

Every restore goes into a **new instance**: the damaged one stays untouched until the new one is verified, and the
switch is a reviewed change of `DB_URL`. `make dr-restore-*` wraps `scripts/dr/restore.sh`; add `DRY_RUN=1` to see the
commands first. The CLIs need the operator's credentials for the environment (the same identities as
[infrastructure.md § 1](infrastructure.md)).

### Point in time (bad migration, deleted rows, corruption)

1. Pick the time: just before the damage, in UTC (deploy time from Argo CD / `helm history`, the first error in the
   logs, the `at` of the first bad row).
2. Restore into a new instance:
   ```sh
   make dr-restore-pitr CLOUD=aws DR_ENV=prod TIME=2026-10-02T13:05:00Z        # DRY_RUN=1 first
   ```
   AWS `restore-db-instance-to-point-in-time` (same subnet group, security group and parameter group); Google Cloud
   `gcloud sql instances clone --point-in-time` (same network and key); Azure
   `az postgres flexible-server restore --restore-time` (same delegated subnet).
3. [Verify](#verifying-a-restore) against the live database up to the restore time:
   ```sh
   make dr-verify SOURCE_URL=postgresql://…live… TARGET_URL=postgresql://…restored… AS_OF=2026-10-02T13:05:00Z ONLY=flyway,asof
   ```
4. Decide: switch to the restored database (step 5), or copy the lost rows back into the live one by hand.
5. Switch: `make dr-point DR_ENV=prod URL='jdbc:postgresql://<host>:5432/northline?sslmode=require'` writes
   `configEnv.DB_URL` into `deploy/argocd/envs/prod/values.yaml` (it layers over Terraform's `infra.yaml`); PR,
   review, Argo CD sync (the migration Job runs first and must find the restored Flyway version). `DB_PASSWORD`
   does not change: the restored database has the same roles and passwords.
6. [After a database restore](#after-a-database-restore); later, import the new instance into Terraform (or apply a
   Terraform change that adopts it) and delete the old one.

### From a backup or snapshot

```sh
make dr-restore-snapshot CLOUD=gcp DR_ENV=prod                  # latest; BACKUP=<id> for a given one
```

AWS: an automated or manual DB snapshot; Google Cloud: a backup restored into a new instance shaped like the source;
Azure: Flexible Server has no user snapshots — a backup (automatic, or `az postgres flexible-server backup create`
before a risky change) is restored as the point in time it completed at. Verify with all kinds (no `ONLY`) against a
fingerprint taken when the backup was made, or `ONLY=flyway`.

### Regional disaster (prod)

1. Declare it (DR lead, [roles](#roles-and-the-quarterly-drill)); freeze deploys.
2. Stand the environment up in the secondary region: `terraform apply` in `infra/terraform/envs/<cloud>/prod` with
   `region` = the secondary region (`ca-west-1`, `northamerica-northeast2` or `canadaeast`; or the primary, if prod
   ran in the secondary) and a new state key; skip the data stores that come from the copy.
3. Database:
   - **AWS**: `make dr-restore-regional CLOUD=aws` with `--subnet-group` / `--security-group` of the new network
     (restore.sh flags): a point-in-time restore from the replicated automated backups.
   - **Google Cloud**: `make dr-restore-regional CLOUD=gcp` promotes `<instance>-dr` and turns on its backups and PITR.
   - **Azure**: `make dr-restore-regional CLOUD=azure` with `--subnet-id` / `--private-dns-zone`: a geo-restore.
4. Object storage: point `STORAGE_BUCKET` (and `STORAGE_REGION` on AWS, `STORAGE_ENDPOINT` on Azure) at the replica
   (`terraform output -json backup` → `storage.replica_buckets`), grant the api's new identity on it.
5. Secrets: re-create them in the new region's store (§ [Known gaps](#known-gaps)).
6. Search: a new Elastic Cloud deployment in the region, then the full reindex. Kafka and Valkey: new and empty.
7. DNS: the public hosts follow the new Gateway (external-dns, [edge.md](edge.md)).

## Prod snapshot to staging

Staging gets prod's shape and volume without prod's personal data. **Unmasked prod data never reaches staging**: the
copy is restored and masked inside prod's boundary, checked, and only the masked dump crosses over.

1. Restore prod's latest backup into a temporary instance **in prod's account/project/subscription**:
   `make dr-restore-snapshot CLOUD=<cloud> DR_ENV=prod NAME=northline-prod-pg-mask-$(date +%Y%m%d)`.
2. Scale staging's apps to zero (Argo CD: suspend auto-sync where on, scale the Deployments).
3. From an operator machine or Job that reaches both databases (port-forwards through each cluster):
   ```sh
   make dr-prod-to-staging OWNER_ROLE=northline_app \
     SOURCE_URL='postgresql://northline_admin:…@localhost:15432/northline?sslmode=require' \
     TARGET_URL='postgresql://northline_admin:…@localhost:25432/northline?sslmode=require'
   ```
   `scripts/dr/prod-to-staging.sh`: masks the source (`db/dr/mask/*.sql`, one transaction), runs `mask-check.sql`
   (any finding stops everything before staging is touched), fingerprints, `pg_dump`, drops and re-creates staging's
   database, creates the extensions as the admin, `pg_restore`s as the owner role, fingerprints staging (must equal
   the masked source) and runs the mask check on staging too. It prints each step's timing.
4. Follow the printed steps: Valkey `FLUSHALL`, the `search-reindex` Job, a chart sync (the oauth-clients Job writes
   staging's client secrets — masking removed prod's), scale up. Testers sign up again (masking removed every
   passkey, TOTP secret, session and federated link). Uploaded files are not copied: images show as missing.
5. Delete the temporary instance: `scripts/dr/restore.sh delete --cloud <cloud> --env prod --name <it>`.
6. Record the run in the [drill log](#drill-log).

### What masking does

One file per schema in `db/dr/mask/` (the per-schema masking the backlog asks for); the privacy officer reviews
changes to them.

| schema | masked |
|---|---|
| identity | e-mail → `u-<id>@example.invalid`, phone → `+1555…` (unique by row), names, pronouns, birthdays; street, unit, Google place id and access notes of addresses (city, province and the postal code's first three characters stay; the point moves ~1 km); household names; **sessions and passkeys deleted** |
| auth | **tokens, consents, TOTP secrets, backup codes, WebAuthn credentials and federated links deleted**; WebAuthn user names pseudonymised; confidential clients' secrets cleared |
| merchants | legal names (merchant and principals), work e-mail, business and GST numbers (unique, valid format), registered office and owner details in `legal_details`, identity-check and registry-match details, invitations, document file names, staff notes, licence references; custom domains removed; locations ~1 km. The public storefront (display name, profile, menus, listings) stays |
| booking, orders, fulfilment | job addresses, customer notes, gate codes (`booking.access_notes` deleted), drop-off documents, the hand-off PIN, line notes, event locations ~1 km |
| messaging | message bodies, sender and counterpart names, subjects, support-case context and notes, notification payloads, upload file names; **push devices deleted** (no push to a real phone) |
| payments | customer names, dispute statements/responses/evidence, bank holder/transit/institution/last 4, card last 4, staff notes, raw Stripe payloads; idempotency records deleted. Amounts, states and the ledger stay |
| developer | API keys revoked (hash replaced); **webhook endpoints switched off and pointed at `webhooks.example.invalid`**; audit snapshots scrubbed (the append-only trigger is off for the transaction only) |
| availability, catalogue, food | calendar / store / POS connections: sealed prod credentials removed, state → reconnect; time-off reasons, vetting notes |
| trust, ai, account, region | review text/replies/authors/reports (the immutability trigger is off for the transaction only), flag evidence, AI prompts, dietary/allergy/accessibility preferences, waitlist e-mails |

`db/dr/mask-check.sql` then proves it: invariants per table (no session, token, passkey, push device, live webhook,
usable API key or sealed credential left; every e-mail and phone masked) **and** a scan of every text and JSON
column of every application schema for e-mail addresses outside `example.invalid` / documentation / platform domains,
North American phone numbers outside the 555 range and full Canadian postal codes. The scan is the safety net for a
column a later migration adds without updating the masking: it fails the refresh until a mask is written. Public
business content (merchant profiles, help articles, policies) is skipped (`northline.mask_check_skip`).

**Overlap with S-105** (privacy rights, not merged when this was written): its per-module `PersonalDataContributor`s
name the personal columns for export and erasure — the same inventory as `db/dr/mask/`. When it merges, a test that
every column a contributor erases is also masked (or mask-check-covered) keeps the two from drifting.

## Verifying a restore

`scripts/dr/verify.sh` (`make dr-verify`) runs `db/dr/fingerprint.sql` on two databases and compares:

| kind | what |
|---|---|
| `flyway` | the schema version Flyway reports (latest successful migration), the number of migrations, failed ones |
| `rows` | exact row count of every application table |
| `checksum` | rows + an order-independent sum of row hashes of the key tables (users, merchants, offers, bookings, orders, payments, ledger, reviews, outbox, Flyway history) — constant memory, prod-sized tables are fine |
| `asof` | the same for the append-only tables (ledger, audit log, Flyway history), over the rows written up to `AS_OF` |

A **snapshot or dump** restore must match on every kind. A **point-in-time** restore is compared with the live
database on `ONLY=flyway,asof AS_OF=<restore time>`: whatever happened after that time, everything append-only before
it must be identical. Then a person checks the reason for the restore (the deleted rows are back, the bad migration
is not applied) and the apps' health once pointed at it.

## After a database restore

- **Search**: run the `search-reindex` Job ([search.md § 9](search.md#9-reindex-s-71)) — the index must match
  the restored database.
- **Kafka**: nothing to restore. Consumers dedupe; the outbox re-publishes pending events. If the restored database
  is older than events consumers already processed, those events are not replayed (their effects are in the
  database or harmless).
- **Valkey**: `FLUSHALL` when the restored database is older than the cache (holds and idempotency keys refer to the
  lost data); users sign in again.
- **Object storage**: rows restored to an older time may point at objects deleted since — restore those versions
  (S3 `ListObjectVersions` / `gsutil ls -a` / blob versions) within the 30-day window.
- **Stripe**: payments that happened after the restore time exist in Stripe but not in the database; the
  Stripe-vs-ledger reconciliation (S-85, `payments.reconciliation_*`) lists them for the finance on-call.

## Local drill

`make dr-drill` (or `scripts/dr/drill-local.sh`) runs the whole cycle with Docker only, in containers it starts
(`nl-dr-*`) and removes — never your own database or containers:

1. a source PostGIS 17 container with WAL archiving (`archive_command` → a local folder), loaded with Flyway +
   the dev seed (Gradle), or a copy of a database (`DR_FROM_URL=postgresql://…`), plus `DR_ROWS` synthetic ledger rows;
2. a physical base backup (`pg_basebackup -Ft -z -X stream`) and a logical dump (`pg_dump -Fc`);
3. a restore point T after a marker row, then a "disaster" after T (reviews truncated, every user renamed);
4. a point-in-time restore into a second container (base backup + archived WAL replayed to T, promoted);
5. verification: the fingerprint equals the source's at T, the marker is there, the disaster is not;
6. the dump restored into a third container and verified against the source at dump time;
7. `prod-to-staging.sh` from the PITR copy into that third container: masking, mask check, dump, restore, verify.

It writes the timings to `results.md` in its work folder (`DR_WORK`, default `$TMPDIR/northline-dr`). `KEEP=1` keeps
the containers for a look.

### Drill log

| date | where | by | result |
|---|---|---|---|
| 2026-10-02 | local (S-114 implementation), Linux x86_64, 4 CPU shared with other builds, Docker 29.3.1, `postgis/postgis:17-3.5`; source = a copy of a Flyway-migrated database (V245, dev seed) + 200 000 ledger rows, 76 MB | S-114 | **passed**: PITR fingerprint = source at T (205 facts), marker kept, disaster undone; dump restore identical; prod → staging masked, mask check passed on both sides, fingerprints identical |

Timings of that run (seconds; the machine was shared with other builds, so treat them as an order of magnitude):

| step | s |
|---|---:|
| start the source (PostGIS 17, WAL archiving) | 9.5 |
| load the source (copy + 200 000 ledger rows; 76 MB) | 12.1 |
| physical base backup (`pg_basebackup -Ft -z -X stream`; 15 MB) | 6.5 |
| logical dump (`pg_dump -Fc`; 2.4 MB) | 4.5 |
| **point-in-time restore to T** (base backup + WAL replay, promoted) | **7.0** |
| verify the PITR copy | 5.0 |
| restore the logical dump into a fresh instance and verify | 18.2 |
| **prod → staging** from the PITR copy (total) | **37.4** |
| — mask + mask check (source) | 9.9 |
| — fingerprint (source) | 3.6 |
| — `pg_dump` | 2.2 |
| — re-create the staging database + extensions | 5.7 |
| — `pg_restore` (4 jobs) | 5.5 |
| — verify (fingerprints identical) + mask check (staging) | 10.2 |

Measured RPO: the last commit before T (0.18 s before it) was recovered and nothing after T was — WAL archiving loses
at most the WAL segment not yet archived (`archive_timeout` = 60 s in the drill; the managed services ship their logs
every ~5 min, which is where the 5-minute PITR target comes from). The local RTO is seconds because the database is
small; on the clouds a restore creates an instance (tens of minutes) and replays logs proportional to the write volume
since the last backup — the first cloud drill measures it.

## Roles and the quarterly drill

| role | who | does |
|---|---|---|
| **DR lead** | the platform on-call engineer (on-call rota, S-113 when merged) | declares the incident or the drill, picks the restore point, owns the timeline and the go/no-go for switching `DB_URL` |
| **Database operator** | a second platform engineer | runs `restore.sh` / `prod-to-staging.sh`, verifies, records timings |
| **Application owner** | the on-call developer of the affected module | confirms the restored data is right (the deleted rows are back, the app works) |
| **Privacy officer** | S-105's privacy officer role | approves every prod → staging refresh and every change to `db/dr/mask/` |
| **Communications** | support lead | status page (messaging status components) and merchants/customers if data was lost |

Every quarter, in the first two weeks, on staging or a temporary copy — never by breaking prod:

| quarter | drill | pass when |
|---|---|---|
| Q1 (January) | **PITR**: restore prod to a time 1 h ago into a temporary instance, verify `flyway,asof`, delete it | verified; RTO measured against 2 h |
| Q2 (April) | **prod → staging** refresh with masking (the acceptance path of S-114) | mask check passes on both sides, fingerprints identical, staging works after the reindex |
| Q3 (July) | **regional**: in staging's account, stand staging up in the secondary region from its copy (AWS replicated backups / Google Cloud replica / Azure geo-restore; turn `backup.cross_region` on for staging for the drill) and point it at the bucket replica | RTO measured against 4 h; the Terraform apply in the secondary region timed |
| Q4 (October) | **game day**: object storage version restore, search rebuild from scratch, Kafka topics re-created, Valkey flushed, a PITR restore — together | every step in this runbook still matches reality |

Also: `make dr-drill` before merging any change to `db/dr/`, `scripts/dr/` or a migration that adds personal data
(the mask check fails on unmasked e-mails, phones and postal codes). Each drill adds a row to the [drill log](#drill-log)
and updates the targets above with measured numbers.

## Alerts

Signals each cloud exposes for a failing backup or copy — to be wired as pager alerts with the alerting work (S-113 was
not merged when this was written; add the links from its runbook when it is):

| | AWS | Google Cloud | Azure |
|---|---|---|---|
| backups | RDS event subscription, category `backup` (failure) and `recovery`; `LatestRestorableTime` older than 15 min | Cloud SQL `backup` operations with status `FAILED` (Cloud Logging), `cloudsql.googleapis.com/database/...` uptime of the replica | Azure Monitor activity log for the server; `backup_storage_used` stops growing |
| database copy | `describe-db-instance-automated-backups` in the secondary region: `RestoreWindow.LatestTime` lag | `cloudsql.googleapis.com/database/replication/replica_lag` > 60 s, replica not `RUNNABLE` | geo-backup: none exposed (check the geo-restore point in the portal during drills) |
| bucket replica | S3 replication metrics: `OperationsFailedReplication` > 0, `ReplicationLatency` > 900 s (enabled by Terraform) | Storage Transfer job operations with failures (`storagetransfer.googleapis.com` metrics, `FAILED` copy logs enabled) | object replication policy metrics (`metrics_enabled`): pending operations / bytes |

## Known gaps

- **Never run in a cloud.** Every RPO/RTO above is a target until the first cloud drill.
- **Same account.** Backups and copies live in the same AWS account / Google Cloud project / Azure subscription as
  prod: a compromised or deleted account takes them too. Next step — **S-149** in the backlog (owner decision
  2026-10-04, not built): AWS Backup with a locked vault in a separate account, Google Cloud Backup and DR / a locked
  bucket in another project, Azure Backup vault with immutability.
- **Secrets in the secondary region**: Google Cloud secrets are replicated in the primary region only, AWS Secrets
  Manager secrets are not replicated; a regional disaster means re-creating them in the new region (they are created
  empty by Terraform; the values come from the operators' password manager). Secret replication to the secondary
  Canadian region is a follow-up.
- **Elasticsearch** has no copy in the secondary region (a reindex is the plan); the `found-snapshots` repository is in
  the deployment's region.
- **Google Cloud deletes** are not propagated to the bucket replica, and Cloud SQL's PITR window is 7 days (Enterprise
  edition) against 35 elsewhere; Enterprise Plus would give 35.
- **Azure geo-redundant backup** can only be set when the server is created; staging and dev don't have it.
- The masking is a SQL inventory: a new personal column needs a line in `db/dr/mask/` (the scan catches e-mails,
  phones and postal codes, not names or free text).
