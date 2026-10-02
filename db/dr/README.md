# Disaster recovery SQL (S-114)

Not migrations: nothing here is applied by Flyway. `scripts/dr/*.sh` runs these files with `psql`
(docs/runbooks/backups-dr.md).

| file | what |
|---|---|
| `mask/*.sql` | turns a restored **prod** copy into staging data: personal data replaced, credentials and device tokens removed. One file per schema, applied in name order in **one transaction** by `scripts/dr/mask.sh`, as the role that owns the tables (`northline_app` on the clouds) |
| `mask-check.sql` | fails unless the masking held: fixed invariants per table plus a scan of every text/JSON column for e-mail addresses and phone numbers |
| `fingerprint.sql` | Flyway version, row counts and checksums of the key tables; `scripts/dr/verify.sh` compares two databases with it |
