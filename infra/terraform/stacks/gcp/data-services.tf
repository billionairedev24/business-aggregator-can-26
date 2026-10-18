# dryrun (docs/runbooks/dryrun-gcp.md, docs/DECISIONS.md 2026-10-18): data services that are not the managed data stores
# of S-3. Each of Postgres, Valkey, Meilisearch and Kafka runs either in the cluster (chart deploy/helm/northline-data,
# pinned to the cluster's zone) or at an external provider (e.g. Layerbase). This file provides what Terraform owns for
# them: the credentials in Secret Manager, the Postgres backup bucket and the backup identity, and (outputs.tf) the
# endpoints in config_env.

locals {
  managed_data_stores = var.data_stores != null
  ds                  = var.data_services
  in_cluster = {
    for s in ["postgres", "cache", "search", "kafka"] : s => !local.managed_data_stores && try(local.ds[s].mode, "") == "in-cluster"
  }

  # In-cluster endpoints: the Services of deploy/helm/northline-data in the environment's namespace.
  svc = "${local.namespace}.svc.cluster.local"
  data_service_endpoints = local.managed_data_stores ? null : {
    db_url          = local.in_cluster.postgres ? "jdbc:postgresql://northline-pg-rw.${local.svc}:5432/northline?sslmode=require" : local.ds.postgres.db_url
    db_user         = local.ds.postgres.db_user
    redis_host      = local.in_cluster.cache ? "northline-valkey.${local.svc}" : local.ds.cache.host
    redis_port      = local.in_cluster.cache ? 6379 : local.ds.cache.port
    redis_ssl       = local.in_cluster.cache ? false : local.ds.cache.ssl
    redis_username  = local.in_cluster.cache ? "" : local.ds.cache.username
    meilisearch_url = local.in_cluster.search ? "http://northline-meilisearch.${local.svc}:7700" : local.ds.search.url
    kafka_bootstrap = local.in_cluster.kafka ? "northline-kafka.${local.svc}:9092" : local.ds.kafka.bootstrap
    kafka_protocol  = local.in_cluster.kafka ? "PLAINTEXT" : local.ds.kafka.security_protocol
    kafka_mechanism = local.in_cluster.kafka ? "" : local.ds.kafka.sasl_mechanism
  }

  # Variable => secret name (northline-<env>-<name>). In-cluster: generated below (the services read the same secret
  # through External Secrets). External: created empty; the operator puts the provider's credential in.
  data_service_secrets = local.managed_data_stores ? {} : merge(
    { DB_PASSWORD = "db-app-password", REDIS_PASSWORD = "redis-password", MEILISEARCH_API_KEY = "meilisearch-api-key" },
    local.in_cluster.kafka ? {} : { KAFKA_SASL_JAAS_CONFIG = "kafka-sasl-jaas-config" },
  )
  secret_service    = { DB_PASSWORD = "postgres", REDIS_PASSWORD = "cache", MEILISEARCH_API_KEY = "search", KAFKA_SASL_JAAS_CONFIG = "kafka" }
  generated_secrets = { for var_name, secret in local.data_service_secrets : var_name => secret if local.in_cluster[local.secret_service[var_name]] }

  # CloudNativePG runs the Postgres pods as ServiceAccount <cluster name> (northline-pg); its barman backups write to
  # the backup bucket as this identity.
  data_service_identities = local.in_cluster.postgres ? {
    postgres-backup = { namespace = "northline-${var.environment}", service_account = "northline-pg" }
  } : {}
}

resource "random_password" "data_service" {
  for_each = local.generated_secrets
  length   = 40
  special  = false # URL-, shell- and Valkey-ACL-safe
}

resource "google_secret_manager_secret_version" "data_service" {
  for_each    = local.generated_secrets
  secret      = module.secrets.cloud.secret_ids[each.value]
  secret_data = random_password.data_service[each.key].result
}

# Daily base backups and WAL of the in-cluster Postgres (CloudNativePG barmanObjectStore), in the region, encrypted
# with the data key. CloudNativePG prunes by its retention policy; the lifecycle rule is a safety net.
resource "google_storage_bucket" "postgres_backups" {
  count                       = local.in_cluster.postgres ? 1 : 0
  project                     = var.project_id
  name                        = "${local.name}-pg-backups${var.bucket_name_suffix}"
  location                    = var.region
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = contains(["dev", "dryrun"], var.environment)
  labels                      = local.tags

  encryption {
    default_kms_key_name = module.kms.key_ids["data"]
  }

  lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [module.kms] # the Cloud Storage service agent's grant on the data key
}

resource "google_storage_bucket_iam_member" "postgres_backups" {
  count  = local.in_cluster.postgres ? 1 : 0
  bucket = google_storage_bucket.postgres_backups[0].name
  role   = "roles/storage.objectAdmin"
  member = module.kubernetes.workload_identities["postgres-backup"].principal
}
