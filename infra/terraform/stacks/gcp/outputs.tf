# Env roots re-export these. config_env and secret_env are keyed by the application's environment variable names
# (docs/runbooks/README.md § Variables at a glance), so they can be written straight into a ConfigMap and the
# ExternalSecret mapping.

output "config_env" {
  description = "Non-secret environment variables for the ConfigMap."
  value = merge({
    # api, object storage (S-10, docs/runbooks/object-storage.md). Workload identity: STORAGE_ACCESS_KEY/SECRET_KEY
    # stay unset, STORAGE_PATH_STYLE false.
    STORAGE_PROVIDER       = module.storage.storage_provider
    STORAGE_BUCKET         = module.storage.bucket_names["uploads"]
    STORAGE_REGION         = module.storage.storage_region
    STORAGE_ENDPOINT       = module.storage.storage_endpoint
    STORAGE_ENCRYPTION_KEY = module.storage.storage_encryption_key

    # northline-auth, token signing (S-7, docs/runbooks/key-rotation.md). var.signing_key_ids overrides the key
    # Terraform created during a rotation.
    KMS_PROVIDER          = module.kms.kms_provider
    KMS_KEY_ID            = coalesce(var.signing_key_ids.active, module.kms.key_refs["signing"])
    KMS_PUBLISHED_KEY_IDS = join(",", var.signing_key_ids.published)
    # api, envelope encryption of stored secrets (S-32, docs/runbooks/calendar-sync.md).
    KMS_ENCRYPTION_KEY_ID = module.kms.key_refs["tokens"]
    },
    local.managed_data_stores ? {
      DB_URL                  = one(module.postgres[*].db_url)
      DB_USER                 = one(module.postgres[*].db_user)
      REDIS_HOST              = one(module.cache[*].redis_host)
      REDIS_PORT              = tostring(one(module.cache[*].redis_port))
      REDIS_SSL               = tostring(one(module.cache[*].redis_ssl))
      KAFKA_BOOTSTRAP         = one(module.kafka[*].kafka_bootstrap)
      KAFKA_SECURITY_PROTOCOL = one(module.kafka[*].kafka_security_protocol)
      KAFKA_SASL_MECHANISM    = one(module.kafka[*].kafka_sasl_mechanism)
      ES_URIS                 = one(module.search[*].es_uris)
      ES_USERNAME             = one(module.search[*].es_username)
      } : {
      # dryrun (data-services.tf): in-cluster or external services; search through Meilisearch
      # (northline.search.provider=meilisearch: MEILISEARCH_URL here, MEILISEARCH_API_KEY in secret_env).
      DB_URL                  = local.data_service_endpoints.db_url
      DB_USER                 = local.data_service_endpoints.db_user
      REDIS_HOST              = local.data_service_endpoints.redis_host
      REDIS_PORT              = tostring(local.data_service_endpoints.redis_port)
      REDIS_SSL               = tostring(local.data_service_endpoints.redis_ssl)
      REDIS_USERNAME          = local.data_service_endpoints.redis_username
      KAFKA_BOOTSTRAP         = local.data_service_endpoints.kafka_bootstrap
      KAFKA_SECURITY_PROTOCOL = local.data_service_endpoints.kafka_protocol
      KAFKA_SASL_MECHANISM    = local.data_service_endpoints.kafka_mechanism
      SEARCH_PROVIDER         = "meilisearch"
      MEILISEARCH_URL         = local.data_service_endpoints.meilisearch_url
    },
  )
}

output "secret_env" {
  description = "Secret environment variables => secret reference in the cloud secrets manager (ExternalSecret remoteRef.key)."
  value = merge(
    { for var_name, secret in local.app_secrets : var_name => module.secrets.secret_refs[secret] },
    local.managed_data_stores ? {
      DB_PASSWORD            = one(module.postgres[*].db_password_secret_ref)
      KAFKA_SASL_JAAS_CONFIG = one(module.kafka[*].kafka_sasl_jaas_config_secret_ref)
      ES_PASSWORD            = one(module.search[*].es_password_secret_ref)
    } : { for var_name, secret in local.data_service_secrets : var_name => module.secrets.secret_refs[secret] },
  )
}

output "secrets_provider" {
  description = "External Secrets Operator provider (SecretStore spec.provider)."
  value       = module.secrets.secrets_provider
}

output "external_secrets" {
  description = "External Secrets Operator store settings for the Helm chart (externalSecrets.provider and its block, S-6)."
  value = {
    provider = "gcp"
    gcp      = { projectID = var.project_id }
  }
}

output "secret_store" {
  description = "Where generated secrets are written."
  value       = module.secrets.store
}

output "network" {
  description = "Network ids."
  value = {
    network_id         = module.network.network_id
    cidr               = module.network.cidr
    cluster_subnet_ids = module.network.cluster_subnet_ids
    data_subnet_ids    = module.network.data_subnet_ids
    cloud              = module.network.cloud
  }
}

output "kubernetes" {
  description = "Cluster access and workload identities (ServiceAccount annotations for Helm, S-14)."
  value = {
    cluster_name        = module.kubernetes.cluster_name
    cluster_endpoint    = module.kubernetes.cluster_endpoint
    oidc_issuer_url     = module.kubernetes.oidc_issuer_url
    kubeconfig_command  = module.kubernetes.kubeconfig_command
    workload_identities = module.kubernetes.workload_identities
  }
}

output "registry" {
  description = "Container registry."
  value = {
    registry_url    = module.registry.registry_url
    repository_urls = module.registry.repository_urls
    login_command   = module.registry.cloud.login_command
  }
}

output "dns" {
  description = "DNS zone; create the name_servers as NS records in the parent zone."
  value = {
    zone_name    = module.dns.zone_name
    name_servers = module.dns.name_servers
  }
}

output "kms" {
  description = "KMS keys."
  value = {
    key_ids  = module.kms.key_ids
    key_refs = module.kms.key_refs
  }
}

# Google Cloud: no REDIS_PASSWORD (Memorystore for Valkey has no static password; access is limited to the VPC).
# dryrun: postgres/cache/kafka/search are null; data_services says what runs where.
output "data_stores" {
  description = "Data store details for operators: admin secrets, topic provisioning, TLS notes; data_services = the dryrun's in-cluster / external services."
  value = {
    postgres = local.managed_data_stores ? {
      host             = module.postgres[0].db_host
      database         = module.postgres[0].db_name
      app_user         = module.postgres[0].db_user
      admin_secret_ref = module.postgres[0].admin_secret_ref
      cloud            = module.postgres[0].cloud
    } : null
    cache = local.managed_data_stores ? {
      host  = module.cache[0].redis_host
      port  = module.cache[0].redis_port
      cloud = module.cache[0].cloud
    } : null
    kafka = local.managed_data_stores ? {
      bootstrap          = module.kafka[0].kafka_bootstrap
      replication_factor = module.kafka[0].kafka_replication_factor
      topic_policy       = module.kafka[0].kafka_topic_policy
      cloud              = module.kafka[0].cloud
    } : null
    search = local.managed_data_stores ? {
      es_uris = module.search[0].es_uris
      cloud   = module.search[0].cloud
    } : null
    data_services = local.managed_data_stores ? null : {
      modes     = { for s, in_cluster in local.in_cluster : s => in_cluster ? "in-cluster" : "external" }
      endpoints = local.data_service_endpoints
      # Secrets the operator fills (external services); the in-cluster ones are generated.
      secrets_to_fill = [for var_name, secret in local.data_service_secrets : module.secrets.secret_refs[secret] if !contains(keys(local.generated_secrets), var_name)]
      # Provider allow-lists (external services): the cluster reaches the internet through these Cloud NAT addresses.
      egress_ips = module.network.cloud.nat_public_ips
    }
  }
}

# dryrun: values for deploy/helm/northline-data (the in-cluster services), see the env root's data_values output.
output "data_services" {
  description = "In-cluster data services (dryrun): which run, the zone their volumes live in, the Postgres backup bucket and identity, the remote keys of their credentials."
  value = local.managed_data_stores ? null : {
    zone      = coalesce(var.zone, "${var.region}-a")
    namespace = local.namespace
    enabled   = local.in_cluster
    postgres_backup = local.in_cluster.postgres ? {
      bucket                      = google_storage_bucket.postgres_backups[0].name
      service_account_annotations = module.kubernetes.workload_identities["postgres-backup"].service_account_annotations
    } : null
    remote_keys = { for var_name, secret in local.generated_secrets : var_name => module.secrets.secret_refs[secret] }
  }
}

# S-17: the edge add-ons' values for this environment (deploy/argocd/envs/<env>/addons/*.yaml via the env root's
# gitops_addon_values) and the cert-manager DNS-01 solver for the chart's Issuer.
output "edge" {
  description = "DNS zone details for external-dns and cert-manager, with their workload identities."
  value = {
    zone_name          = module.dns.zone_name
    cert_manager_dns01 = module.dns.cert_manager_dns01
    external_dns       = module.dns.external_dns
    identities = {
      for name in ["external-dns", "cert-manager"] : name => {
        service_account_annotations = module.kubernetes.workload_identities[name].service_account_annotations
        pod_labels                  = module.kubernetes.workload_identities[name].pod_labels
      }
    }
  }
}

# S-114: what protects each data store and where the copies are (docs/runbooks/backups-dr.md); scripts/dr/restore.sh
# reads it (`terraform output -json backup`).
output "backup" {
  description = "Backups and disaster recovery: the database's backups and secondary-region copy, the bucket replicas, and the stores that are rebuilt rather than restored."
  value = {
    primary_region   = var.region
    secondary_region = var.backup.cross_region ? local.secondary_region : ""
    postgres         = local.managed_data_stores ? merge(module.postgres[0].backup, { instance = module.postgres[0].cloud.instance }) : null
    # dryrun: CloudNativePG base backups + WAL in this bucket (in-cluster Postgres); external = the provider's own.
    dryrun_postgres_bucket = local.in_cluster.postgres ? google_storage_bucket.postgres_backups[0].name : null
    storage = {
      buckets         = module.storage.bucket_names
      replica_buckets = module.storage.replica_bucket_names
      replica_region  = module.storage.replica_region
    }
    # Elastic Cloud snapshots every deployment to its managed found-snapshots repository (policy
    # cloud-snapshot-policy); restoring is optional, the read model is rebuilt from Postgres (search-reindex Job).
    search = { snapshot_repository = "found-snapshots", snapshot_policy = "cloud-snapshot-policy", rebuild = "search-reindex" }
    kafka  = { backed_up = false, rebuild = "topics re-created by the provisioning Job; the outbox re-publishes what was not delivered" }
    cache  = { backed_up = false, lost = "sessions (sign in again), caches, rate-limit counters, idempotency keys, slot holds, live tracking" }
  }
}
