# The env roots re-export these. config_env is keyed by the apps' environment variable names (docs/runbooks/README.md
# § Variables at a glance), the same keys as the other clouds' config_env.

output "config_env" {
  description = "Non-secret environment variables for the ConfigMap (the chart's configEnv)."
  value = {
    # api, object storage (S-10): OVHcloud Object Storage over the S3 API, static keys (secret: STORAGE_ACCESS_KEY,
    # STORAGE_SECRET_KEY). Path-style addressing: works on every S3-compatible endpoint.
    STORAGE_PROVIDER       = "s3"
    STORAGE_BUCKET         = ovh_cloud_project_storage.bucket["uploads"].name
    STORAGE_REGION         = local.r.s3_region
    STORAGE_ENDPOINT       = local.r.s3_endpoint
    STORAGE_PATH_STYLE     = "true"
    STORAGE_ENCRYPTION_KEY = ""

    # No key service the apps support on OVHcloud: northline-auth signs with keys in a file (SIGNING_KEYS_DIR, on the
    # authSigningKeys volume), the api seals with KMS_LOCAL_KEY (secret). Allowed under `dev` only.
    KMS_PROVIDER = "local"

    DB_URL                  = local.endpoints.db_url
    DB_USER                 = local.endpoints.db_user
    REDIS_HOST              = local.endpoints.redis_host
    REDIS_PORT              = tostring(local.endpoints.redis_port)
    REDIS_SSL               = tostring(local.endpoints.redis_ssl)
    REDIS_USERNAME          = local.endpoints.redis_username
    KAFKA_BOOTSTRAP         = local.endpoints.kafka_bootstrap
    KAFKA_SECURITY_PROTOCOL = local.endpoints.kafka_protocol
    KAFKA_SASL_MECHANISM    = local.endpoints.kafka_mechanism
    SEARCH_PROVIDER         = "meilisearch"
    MEILISEARCH_URL         = local.endpoints.meilisearch_url
  }
}

output "in_cluster" {
  description = "Which data services run in the cluster (deploy/helm/northline-data), per service."
  value       = local.in_cluster
}

output "data_values" {
  description = "Values for deploy/helm/northline-data: no zone pinning, the StorageClass, which services run in the cluster, the Postgres backup bucket on Object Storage, secrets from scripts/ovh/secrets.sh, the auth signing-key volume."
  value = {
    pinToZone        = false
    storageClassName = var.storage_class_name
    postgres = {
      enabled = local.in_cluster.postgres
      backup = local.in_cluster.postgres ? {
        enabled         = true
        provider        = "s3"
        bucket          = ovh_cloud_project_storage.bucket["pg-backups"].name
        retentionPolicy = "${var.postgres_backup_retention_days}d"
        s3              = { endpointURL = local.r.s3_endpoint, credentialsSecret = "northline-pg-backup-s3" }
      } : { enabled = false, provider = "s3", bucket = "", retentionPolicy = "${var.postgres_backup_retention_days}d", s3 = { endpointURL = "", credentialsSecret = "northline-pg-backup-s3" } }
    }
    valkey          = { enabled = local.in_cluster.cache }
    meilisearch     = { enabled = local.in_cluster.search }
    kafka           = { enabled = local.in_cluster.kafka }
    externalSecrets = { provider = "none" }
    authSigningKeys = { enabled = true }
  }
}

output "storage" {
  description = "Object Storage: buckets, S3 endpoint and region, the access key ids (the secret keys are in s3_credentials)."
  value = {
    region        = local.r.storage_region
    s3_endpoint   = local.r.s3_endpoint
    s3_region     = local.r.s3_region
    buckets       = { for k, b in ovh_cloud_project_storage.bucket : k => b.name }
    access_key_id = { for k, c in ovh_cloud_project_user_s3_credential.s3 : k => c.access_key_id }
  }
}

output "s3_credentials" {
  description = "S3 keys per writer (uploads: the api; pg-backups: CloudNativePG). Read by scripts/ovh/secrets.sh — never printed."
  sensitive   = true
  value = { for k, c in ovh_cloud_project_user_s3_credential.s3 : k => {
    access_key_id     = c.access_key_id
    secret_access_key = c.secret_access_key
    region            = local.r.s3_region
  } }
}

output "database_region" {
  description = "Managed Databases region of the location (e.g. US-EAST-VA, BHS)."
  value       = local.r.database_region
}

output "location" {
  description = "The location (locations.json) and everything derived from it: country (data residency), API endpoint and token page, Public Cloud region, S3 endpoint and region, VPS datacentre, what the region offers."
  value       = merge(local.r, { name = var.location })
}
